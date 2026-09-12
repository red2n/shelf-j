package com.shelfj.pricing.messaging;

import com.shelfj.ids.Ids;
import com.shelfj.pricing.domain.Domain.InputTaxTransaction;
import com.shelfj.pricing.repo.PricingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Projects {@code SupplierInvoiceCaptured} (purchase-svc) into input VAT (SJ-D39): the figures box
 * 4 and box 7 of the VAT return are made of, keyed by invoice date as the tax point.
 *
 * <p>Idempotent on the event id — which is the invoice id — so a redelivered event records nothing
 * twice. A malformed event is logged and skipped, never retried into a loop; a transient database
 * failure propagates so the consumer loop redelivers.
 */
@ApplicationScoped
public class SupplierInvoiceEventHandler {

  private static final Logger LOG = System.getLogger(SupplierInvoiceEventHandler.class.getName());

  @Inject PricingRepository repo;

  /**
   * Handles one event payload.
   *
   * @param json the event as published
   * @return true when a row was recorded; false when skipped or already recorded
   */
  public boolean handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    UUID invoiceId;
    BigDecimal net;
    BigDecimal vat;
    BigDecimal gross;
    Instant taxPoint;
    try {
      obj = Json.createReader(new StringReader(json)).readObject();
      if (!"SupplierInvoiceCaptured".equals(obj.getString("eventType", ""))) {
        return false;
      }
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      invoiceId = UUID.fromString(obj.getString("invoiceId"));
      net = obj.getJsonNumber("netAmount").bigDecimalValue();
      vat = obj.getJsonNumber("vatAmount").bigDecimalValue();
      gross = obj.getJsonNumber("grossAmount").bigDecimalValue();
      taxPoint =
          LocalDate.parse(obj.getString("invoiceDate")).atStartOfDay(ZoneOffset.UTC).toInstant();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed SupplierInvoiceCaptured skipped: " + e.getMessage());
      return false;
    }
    var row =
        new InputTaxTransaction(
            Ids.newId(),
            tenantId,
            eventId,
            invoiceId,
            optionalUuid(obj, "poId"),
            optionalUuid(obj, "supplierId"),
            obj.getString("invoiceNumber", null),
            obj.getString("currency", null),
            net,
            vat,
            gross,
            taxPoint);
    boolean recorded = repo.recordInputTaxOnce(row);
    LOG.log(
        Level.INFO,
        "SupplierInvoiceCaptured {0}: {1}",
        invoiceId,
        recorded ? "input VAT " + vat + " recorded" : "already recorded");
    return recorded;
  }

  private static UUID optionalUuid(JsonObject obj, String name) {
    String v = obj.getString(name, null);
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
