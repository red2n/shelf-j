package com.shelfj.notification.messaging;

import com.shelfj.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Sends a supplier its remittance advice when a payment run pays it (17.10): which invoices the
 * payment settles, which credit notes it offsets, and the total, so the supplier's accounts clerk
 * can allocate the money without ringing the shop.
 *
 * <p>Idempotent on the event id, which purchase-svc derives from the run and the supplier. A
 * supplier with no remittance email on record is skipped, not failed; a malformed payload is logged
 * and skipped.
 */
@ApplicationScoped
public class SupplierRemittanceHandler {

  private static final Logger LOG = System.getLogger(SupplierRemittanceHandler.class.getName());
  static final String TYPE = "SUPPLIER_REMITTANCE";

  @Inject Notifier notifier;

  /**
   * @param json the {@code SupplierRemittanceIssued} payload
   */
  public void handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    UUID supplierId;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!"SupplierRemittanceIssued".equals(obj.getString("eventType", ""))) return;
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      supplierId = UUID.fromString(obj.getString("supplierId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed SupplierRemittanceIssued skipped: " + e.getMessage());
      return;
    }
    String email = obj.getString("remittanceEmail", null);
    if (email == null || email.isBlank()) {
      LOG.log(Level.DEBUG, "No remittance email for supplier {0}; advice not sent", supplierId);
      return;
    }
    String reference = obj.getString("runReference", "");
    String currency = obj.getString("currency", "");
    StringBuilder body =
        new StringBuilder("Remittance advice\n\n")
            .append("Payment ")
            .append(reference)
            .append(" on ")
            .append(obj.getString("paymentDate", ""))
            .append("\nTo ")
            .append(obj.getString("supplierName", ""))
            .append("\n\n");
    if (obj.containsKey("items") && !obj.isNull("items")) {
      for (JsonObject item : obj.getJsonArray("items").getValuesAs(JsonObject.class)) {
        boolean credit = "CREDIT_NOTE".equals(item.getString("type", ""));
        body.append(credit ? "Less credit note " : "Invoice ")
            .append(item.getString("reference", ""))
            .append(
                item.containsKey("documentDate") && !item.isNull("documentDate")
                    ? " of " + item.getString("documentDate")
                    : "")
            .append(": ")
            .append(credit ? "-" : "")
            .append(currency)
            .append(' ')
            .append(item.getJsonNumber("amount").bigDecimalValue().toPlainString())
            .append('\n');
      }
    }
    body.append("\nTotal paid: ")
        .append(currency)
        .append(' ')
        .append(obj.getJsonNumber("total").bigDecimalValue().toPlainString())
        .append("\n\nPlease quote ")
        .append(reference)
        .append(" in any query about this payment.\n");
    notifier.notifyOnce(
        eventId,
        TYPE,
        tenantId,
        supplierId,
        email.trim(),
        "Remittance advice " + reference,
        body.toString());
  }
}
