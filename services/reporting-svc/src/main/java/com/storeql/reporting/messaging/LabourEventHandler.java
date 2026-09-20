package com.storeql.reporting.messaging;

import com.storeql.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Keeps the labour projection from tenant-svc's clock (store operations & workforce).
 *
 * <p>The event carries a store, a day and money, and <b>no person</b>: a labour figure is a fact
 * about a shop's Saturday, and who earned what stays in the service that keeps pay. That is what
 * lets this projection exist at all without putting pay data in a reporting database for ever.
 *
 * <p>Idempotent by the entry it describes rather than by the event id: the same entry may be
 * announced again, and the last word about an entry is the right one. A correction names the entry
 * it replaces and the projection takes that figure back out — a report that counted a corrected day
 * twice would look right and be wrong, which is worse than one missing the day.
 *
 * <p>A field that will not parse is skipped, because it will not parse on redelivery either; a
 * write that fails travels, so the consumer loop delivers it again.
 */
@ApplicationScoped
public class LabourEventHandler {

  private static final Logger LOG = System.getLogger(LabourEventHandler.class.getName());

  @Inject ReportingService service;

  void handle(String json) {
    JsonObject o;
    try (var reader = Json.createReader(new StringReader(json))) {
      o = reader.readObject();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed labour event skipped: {0}", e.getMessage());
      return;
    }
    if (!"LabourRecorded".equals(o.getString("eventType", ""))) return;
    UUID tenantId;
    UUID entryId;
    UUID supersedes;
    UUID storeId;
    LocalDate day;
    long minutes;
    BigDecimal cost;
    String currency;
    try {
      // Each required field is asked for by name rather than trusted: an event missing one is a
      // malformed event, and reading it as a null would be a row about nowhere.
      tenantId = UUID.fromString(required(o, "tenantId"));
      entryId = UUID.fromString(required(o, "aggregateId"));
      supersedes = uuid(o, "supersedes");
      storeId = UUID.fromString(required(o, "storeId"));
      day = LocalDate.parse(required(o, "day"));
      if (!o.containsKey("minutes") || o.isNull("minutes")) {
        throw new IllegalArgumentException("minutes is missing");
      }
      minutes = o.getJsonNumber("minutes").longValue();
      cost = decimal(o, "cost");
      currency = text(o, "currency");
    } catch (IllegalArgumentException | DateTimeParseException | ClassCastException e) {
      LOG.log(
          Level.WARNING,
          "Labour event with a field that will not parse skipped: {0}",
          e.getMessage());
      return;
    }
    service.recordLabour(tenantId, entryId, supersedes, storeId, day, minutes, cost, currency);
  }

  /** A field that must be there, or the event is malformed and says so. */
  private static String required(JsonObject o, String name) {
    String value = text(o, name);
    if (value == null) throw new IllegalArgumentException(name + " is missing");
    return value;
  }

  private static UUID uuid(JsonObject o, String name) {
    String value = text(o, name);
    return value == null ? null : UUID.fromString(value);
  }

  private static String text(JsonObject o, String name) {
    return !o.containsKey(name) || o.isNull(name) ? null : o.getString(name);
  }

  private static BigDecimal decimal(JsonObject o, String name) {
    if (!o.containsKey(name) || o.isNull(name)) return null;
    JsonValue v = o.get(name);
    return v.getValueType() == JsonValue.ValueType.NUMBER
        ? o.getJsonNumber(name).bigDecimalValue()
        : new BigDecimal(o.getString(name));
  }
}
