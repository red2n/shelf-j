package com.storeql.notification.messaging;

import com.storeql.notification.channel.SmsChannel;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Writes to the buyer a recall notice was issued to (GPSR art.35): by email to the shop's customer
 * record, else by text to the number the order or the record holds, and a push to the login's
 * devices besides. The message says what art.36 asks: the headline, the product and its lot, the
 * hazard in plain words, what to do, the remedies to choose from, where to turn, and to pass it on.
 * Each send is keyed on the event, so a redelivery tells nobody twice; a malformed payload is
 * skipped; a buyer with no address at all is logged, not lost — order-svc still holds the notice
 * for when they come back.
 */
@ApplicationScoped
class RecallNoticeIssuedHandler {

  private static final Logger LOG = System.getLogger(RecallNoticeIssuedHandler.class.getName());
  static final String TYPE_EMAIL = "RECALL_NOTICE";
  static final String TYPE_SMS = "RECALL_NOTICE_SMS";
  static final String TYPE_PUSH = "RECALL_NOTICE_PUSH";
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  private record Parsed(
      UUID eventId,
      UUID tenantId,
      UUID customerId,
      UUID loginId,
      String buyerPhone,
      String reference,
      String hazard,
      String reason,
      String customerNotice,
      List<String> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      UUID orderId,
      Instant soldAt,
      List<String> lines) {}

  void handle(String json) {
    Parsed p;
    try (var reader = Json.createReader(new StringReader(json))) {
      p = parse(reader.readObject());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallNoticeIssued payload skipped: " + e.getMessage());
      return;
    }
    String subject = "Product safety recall — " + p.reference();
    String email =
        p.customerId() == null
            ? null
            : customers.emailOf(p.tenantId(), p.customerId()).orElse(null);
    if (email != null) {
      notifier.notifyOnce(
          p.eventId(), TYPE_EMAIL, p.tenantId(), p.customerId(), email, subject, body(p));
    } else {
      String phone = p.buyerPhone();
      if (phone == null && p.customerId() != null) {
        phone = customers.phoneOf(p.tenantId(), p.customerId()).orElse(null);
      }
      if (phone != null && SmsChannel.E164.matcher(phone).matches()) {
        notifier.notifyOnce(
            p.eventId(), TYPE_SMS, p.tenantId(), p.customerId(), phone, subject, text(p), "SMS");
      } else if (p.loginId() == null) {
        LOG.log(
            Level.WARNING,
            "Recall notice for order {0} has no email and no usable number; nobody written to",
            p.orderId());
      }
    }
    if (p.loginId() != null) {
      try {
        notifier.notifyOnce(
            p.eventId(),
            TYPE_PUSH,
            p.tenantId(),
            p.customerId(),
            p.loginId().toString(),
            subject,
            "Stop using " + p.lines().get(0) + ". Open the app for what to do and your remedy.",
            "PUSH");
      } catch (RuntimeException e) {
        LOG.log(Level.DEBUG, "No push for recall notice {0}: {1}", p.orderId(), e.getMessage());
      }
    }
  }

  private static Parsed parse(JsonObject obj) {
    List<String> remedies =
        obj.getJsonArray("remedies").getValuesAs(JsonString.class).stream()
            .map(JsonString::getString)
            .toList();
    List<String> lines =
        obj.getJsonArray("lines").getValuesAs(JsonObject.class).stream()
            .map(RecallNoticeIssuedHandler::line)
            .toList();
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("a recall notice names what was bought");
    }
    return new Parsed(
        UUID.fromString(obj.getString("eventId")),
        UUID.fromString(obj.getString("tenantId")),
        uuid(obj, "customerId"),
        uuid(obj, "loginId"),
        text(obj, "buyerPhone"),
        obj.getString("reference"),
        RecallText.hazard(obj.getString("hazard")),
        obj.getString("reason"),
        obj.getString("customerNotice"),
        remedies,
        text(obj, "singleRemedyReason"),
        text(obj, "contactPhone"),
        text(obj, "contactUrl"),
        UUID.fromString(obj.getString("orderId")),
        Instant.parse(obj.getString("soldAt")),
        lines);
  }

  /** "Crunchy peanut butter (PB-340), lot L1, best before 1 Oct 2026, 2 bought". */
  private static String line(JsonObject l) {
    StringBuilder b = new StringBuilder();
    String name = text(l, "productName");
    String sku = text(l, "sku");
    b.append(name != null ? name : "the product");
    if (sku != null) b.append(" (").append(sku).append(')');
    String lot = text(l, "batchNo");
    if (lot != null) b.append(", lot ").append(lot);
    String expiry = text(l, "expiryDate");
    if (expiry != null) b.append(", best before ").append(expiry);
    b.append(", ").append(l.get("qty").toString()).append(" bought");
    return b.toString();
  }

  /** The written notice, in the order GPSR art.36(2) lists its parts. */
  static String body(Parsed p) {
    StringBuilder b =
        new StringBuilder("PRODUCT SAFETY RECALL\nReference ").append(p.reference()).append("\n\n");
    b.append("What: ").append(String.join("; ", p.lines())).append('\n');
    b.append("Bought on ")
        .append(DAY.format(p.soldAt().atOffset(ZoneOffset.UTC)))
        .append(", order ")
        .append(p.orderId())
        .append("\n\n");
    b.append("Hazard: ").append(p.hazard()).append(". ").append(p.reason()).append("\n\n");
    b.append("What to do: Stop using this product immediately. ")
        .append(p.customerNotice())
        .append("\n\n");
    b.append("Your remedy — you choose: ").append(RecallText.remedies(p.remedies())).append('.');
    if (p.singleRemedyReason() != null) b.append(' ').append(p.singleRemedyReason());
    b.append("\n\nContact: ").append(contact(p)).append('\n');
    b.append("Please pass this notice to anyone you have shared the product with.\n\n— StoreQL");
    return b.toString();
  }

  /** The text: the headline, the product, what to do and where to turn, inside one message. */
  private static String text(Parsed p) {
    return "PRODUCT SAFETY RECALL "
        + p.reference()
        + ": "
        + p.lines().get(0)
        + ". Stop using it now. "
        + p.hazard()
        + ". You may choose "
        + RecallText.remedies(p.remedies())
        + ". Contact "
        + contact(p);
  }

  private static String contact(Parsed p) {
    if (p.contactPhone() != null && p.contactUrl() != null) {
      return p.contactPhone() + " or " + p.contactUrl();
    }
    return p.contactPhone() != null ? p.contactPhone() : p.contactUrl();
  }

  private static String text(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field) ? obj.getString(field) : null;
  }

  private static UUID uuid(JsonObject obj, String field) {
    String value = text(obj, field);
    return value == null ? null : UUID.fromString(value);
  }
}
