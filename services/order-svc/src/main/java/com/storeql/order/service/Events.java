package com.storeql.order.service;

import static com.storeql.events.EventPayload.esc;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.RecallNotice.Line;
import com.storeql.order.domain.RecallNotice.Notice;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@link OutboxRow} instances for all events published by order-svc. Request-supplied
 * strings (channel, cancel reason) are escaped — they must not be able to corrupt event JSON.
 * Public for the one builder a consumer in another package needs; the rest stay package-private.
 */
public final class Events {

  static final String TOPIC_RECALL_NOTICE_ISSUED = "storeql.order.recall-notice-issued";
  static final String TOPIC_GIFT_CARD_LOADED = "storeql.order.gift-card-loaded";

  private Events() {}

  /**
   * A recall notice issued to a buyer this service could identify (05.10): everything
   * notification-svc needs to write to them under GPSR art.36, and the ids it resolves an address
   * from. Built with the JSON API because the notice is free text with line breaks in it.
   */
  public static OutboxRow recallNoticeIssued(Notice n, List<Line> lines) {
    JsonArrayBuilder remedies = Json.createArrayBuilder();
    n.remedies().stream().map(Enum::name).sorted().forEach(remedies::add);
    JsonArrayBuilder items = Json.createArrayBuilder();
    for (Line l : lines) {
      JsonObjectBuilder line =
          Json.createObjectBuilder().add("variantId", l.variantId().toString()).add("qty", l.qty());
      nullable(line, "productName", l.productName());
      nullable(line, "sku", l.sku());
      nullable(line, "batchNo", l.batchNo());
      nullable(line, "expiryDate", l.expiryDate() == null ? null : l.expiryDate().toString());
      items.add(line);
    }
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "RecallNoticeIssued")
            .add("tenantId", n.tenantId().toString())
            .add("aggregateId", n.id().toString())
            .add("occurredAt", Instant.now().toString())
            .add("noticeId", n.id().toString())
            .add("recallId", n.recallId().toString())
            .add("reference", n.reference())
            .add("hazard", n.hazard())
            .add("reason", n.reason())
            .add("customerNotice", n.customerNotice())
            .add("remedies", remedies)
            .add("orderId", n.orderId().toString())
            .add("storeId", n.storeId().toString())
            .add("channel", n.channel())
            .add("soldAt", n.soldAt().toString())
            .add("lines", items);
    nullable(b, "singleRemedyReason", n.singleRemedyReason());
    nullable(b, "contactPhone", n.contactPhone());
    nullable(b, "contactUrl", n.contactUrl());
    nullable(b, "customerId", n.customerId() == null ? null : n.customerId().toString());
    nullable(b, "loginId", n.loginId() == null ? null : n.loginId().toString());
    nullable(b, "buyerPhone", n.buyerPhone());
    return new OutboxRow(
        "RecallNoticeIssued",
        TOPIC_RECALL_NOTICE_ISSUED,
        n.tenantId(),
        n.id(),
        b.build().toString());
  }

  /**
   * A gift card issued or reloaded, and how it was paid for (17.11): purchase-svc posts the money
   * taken, or the value given away, against the gift card liability, once per card transaction.
   */
  static OutboxRow giftCardLoaded(GiftCard gc, GiftCardTransaction tx, String paidBy) {
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "GiftCardLoaded")
            .add("tenantId", gc.tenantId().toString())
            .add("giftCardId", gc.id().toString())
            .add("transactionId", tx.id().toString())
            .add("kind", tx.txType())
            .add("amount", tx.amount())
            .add("currency", gc.currency())
            .add("paidBy", paidBy);
    nullable(b, "storeId", gc.storeId() == null ? null : gc.storeId().toString());
    return new OutboxRow(
        "GiftCardLoaded", TOPIC_GIFT_CARD_LOADED, gc.tenantId(), gc.id(), b.build().toString());
  }

  private static void nullable(JsonObjectBuilder b, String name, String value) {
    if (value == null) {
      b.addNull(name);
    } else {
      b.add(name, value);
    }
  }

  /**
   * Deposits paid back at the till for containers brought back (09.16): payment-svc records the
   * cash leaving the drawer against the till session, once per event.
   */
  static OutboxRow containerDepositRefunded(com.storeql.order.domain.Domain.ContainerRefund r) {
    return new OutboxRow(
        "ContainerDepositRefunded",
        "storeql.order.container-deposit-refunded",
        r.tenantId(),
        r.id(),
        "{\"eventType\":\"ContainerDepositRefunded\",\"eventId\":\""
            + r.id()
            + "\",\"tenantId\":\""
            + r.tenantId()
            + "\",\"storeId\":\""
            + r.storeId()
            + "\",\"tillSessionId\":\""
            + r.tillSessionId()
            + "\",\"refundedBy\":\""
            + r.refundedBy()
            + "\",\"currency\":\""
            + esc(r.currency())
            + "\",\"containers\":"
            + r.containers()
            + ",\"amount\":"
            + r.amount().toPlainString()
            + "}");
  }

  static OutboxRow orderPlaced(
      UUID tenantId, UUID orderId, String channel, UUID customerId, UUID loginId, UUID storeId) {
    String customerPart =
        customerId != null ? ",\"customerId\":\"" + customerId + "\"" : ",\"customerId\":null";
    // Both ids, because consumers key on different ones: loyalty wants the shop's customer record,
    // while cart-svc holds a shopper's basket under the login their token carries. Until SJ-D44
    // these were the same value in this payload, and the confusion was invisible.
    String loginPart = loginId != null ? ",\"loginId\":\"" + loginId + "\"" : ",\"loginId\":null";
    return new OutboxRow(
        "OrderPlaced",
        "storeql.order.order-placed",
        tenantId,
        orderId,
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"storeId\":\""
            + storeId
            + "\""
            + customerPart
            + loginPart
            + "}");
  }

  /**
   * OrderConfirmed carries an {@code eventId} (consumer dedupe) plus the buyer and settled amount
   * so downstream consumers can react to the sale without a callback to order-svc — customer-svc
   * accrues loyalty from {@code customerId}/{@code total} (guest orders send {@code
   * customerId:null} and earn nothing), and purchase-svc posts the sale to the ledger from {@code
   * total} and {@code taxAmount} (17.7), and reporting-svc records the sale line by line from
   * {@code lines} — each line's variant, quantity, unit price and money — for sales by category
   * (19.x). Emitted exactly once, at full payment (see OrderRepository.applyPaymentCaptured).
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency,
      List<OrderItem> lines) {
    return orderConfirmed(
        tenantId,
        orderId,
        storeId,
        channel,
        customerId,
        total,
        taxAmount,
        currency,
        lines,
        null,
        null,
        null,
        null);
  }

  /**
   * As above, saying how the order is fulfilled and where a delivery goes: purchase-svc raises a
   * dropship supplier's order from this event (consignment and dropship stock ownership), shipped
   * to the customer, so the address rides on the event rather than in a call back to order-svc. The
   * four fields are always present; a till sale carries them as JSON null.
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency,
      List<OrderItem> lines,
      String fulfilmentType,
      String deliveryAddress,
      String deliveryRecipientName,
      String deliveryRecipientPhone) {
    String customerPart = customerId != null ? "\"" + customerId + "\"" : "null";
    String amount = total != null ? total.toPlainString() : "0";
    // The VAT inside the total, so the ledger can post revenue net of it (17.7).
    String tax = taxAmount != null ? taxAmount.toPlainString() : "0";
    String cur = java.util.Objects.requireNonNull(currency, "an order always carries its currency");
    return new OutboxRow(
        "OrderConfirmed",
        "storeql.order.order-confirmed",
        tenantId,
        orderId,
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"storeId\":\""
            + storeId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"customerId\":"
            + customerPart
            + ",\"total\":"
            + amount
            + ",\"taxAmount\":"
            + tax
            + ",\"currency\":\""
            + esc(cur)
            + "\",\"fulfilmentType\":"
            + jsonText(fulfilmentType)
            + ",\"deliveryAddress\":"
            + jsonText(deliveryAddress)
            + ",\"deliveryRecipientName\":"
            + jsonText(deliveryRecipientName)
            + ",\"deliveryRecipientPhone\":"
            + jsonText(deliveryRecipientPhone)
            + ",\"lines\":"
            + confirmedLines(lines)
            + "}");
  }

  /**
   * The sale line by line: what reporting-svc groups by category. Unit price is omitted when
   * unknown.
   */
  /** A JSON string, or JSON null for nothing. */
  private static String jsonText(String value) {
    return value == null || value.isBlank() ? "null" : "\"" + esc(value) + "\"";
  }

  private static String confirmedLines(List<OrderItem> lines) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < lines.size(); i++) {
      OrderItem line = lines.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(line.variantId())
          .append("\",\"qty\":")
          .append(line.qty().toPlainString());
      if (line.unitPrice() != null) {
        sb.append(",\"unitPrice\":").append(line.unitPrice().toPlainString());
      }
      sb.append(",\"lineTotal\":")
          .append(line.lineTotal() != null ? line.lineTotal().toPlainString() : "0")
          .append('}');
    }
    return sb.append(']').toString();
  }

  static OutboxRow orderCancelled(UUID tenantId, UUID orderId, String reason) {
    // eventId lets payment-svc dedupe the automatic refund of a cancelled (paid) order; existing
    // consumers (inventory-svc hold release) ignore the extra field.
    return new OutboxRow(
        "OrderCancelled",
        "storeql.order.order-cancelled",
        tenantId,
        orderId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"OrderCancelled\",\"tenantId\":\"%s\","
                + "\"orderId\":\"%s\",\"reason\":\"%s\"}",
            Ids.newId(), tenantId, orderId, esc(reason)));
  }

  static OutboxRow orderFulfilled(
      UUID tenantId, UUID orderId, UUID storeId, List<OrderItem> items) {
    return orderFulfilled(tenantId, orderId, storeId, items, java.util.Map.of(), 2);
  }

  /**
   * OrderFulfilled with each line's revenue, net of VAT and of the order's discounts ({@code
   * netAmount}), so inventory-svc can set it against the cost of the batches the line draws down
   * (19.7). A line with no price known carries none, and the gross-margin report counts it as
   * unpriced rather than as free.
   *
   * @param unitNet net revenue per unit by variant, from {@code LineRevenue.unitNet}
   * @param scale the currency's minor-unit digits
   */
  static OutboxRow orderFulfilled(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<OrderItem> items,
      java.util.Map<UUID, BigDecimal> unitNet,
      int scale) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it,
    // every OrderFulfilled is dropped as a malformed event and stock is never deducted.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString());
      BigDecimal net =
          com.storeql.order.domain.LineRevenue.forQty(
              unitNet, items.get(i).variantId(), items.get(i).qty(), scale);
      if (net != null) sb.append(",\"netAmount\":").append(net.toPlainString());
      sb.append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderFulfilled", "storeql.order.order-fulfilled", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderReturned(
      UUID tenantId,
      UUID orderId,
      UUID returnId,
      UUID storeId,
      List<ReturnItem> items,
      BigDecimal refundAmount,
      String refundMethod,
      String currency) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it
    // every OrderReturned is dropped as malformed and stock is never restocked. refundAmount +
    // refundMethod let payment-svc reverse the captured payment for ORIGINAL-tender returns.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderReturned\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"returnId\":\"")
        .append(returnId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refundAmount\":")
        .append(refundAmount != null ? refundAmount.toPlainString() : "0")
        .append(",\"refundMethod\":\"")
        .append(esc(refundMethod))
        .append("\",\"currency\":\"")
        .append(esc(currency))
        .append("\",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderReturned", "storeql.order.order-returned", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderVoided(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<com.storeql.order.domain.Domain.RestockLine> restock) {
    // SJ-D40 made a paid till sale deduct stock, so voiding one must put the stock back. items is
    // what to put back: each line net of anything already returned, or empty when the sale was
    // never handed over and nothing was deducted. eventId and storeId are what inventory-svc's
    // OrderEventHandler needs to restock and dedupe per line, exactly as for OrderReturned.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderVoided\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"items\":[");
    for (int i = 0; i < restock.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(restock.get(i).variantId())
          .append("\",\"qty\":")
          .append(restock.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderVoided", "storeql.order.order-voided", tenantId, orderId, sb.toString());
  }

  static OutboxRow layawayCreated(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCreated",
        "storeql.order.layaway-created",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCreated\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCompleted(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCompleted",
        "storeql.order.layaway-completed",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCompleted\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCancelled(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCancelled",
        "storeql.order.layaway-cancelled",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCancelled\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }
}
