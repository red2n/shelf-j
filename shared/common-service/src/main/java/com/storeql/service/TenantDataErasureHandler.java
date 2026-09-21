package com.storeql.service;

import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Erases a departed business's data from this service when tenant-svc says its retrieval period has
 * ended (21.14, EU Data Act art.25(2)): every table its rows sit in, children first, and a {@code
 * TenantDataErased} with the counts written in the same transaction, as the evidence tenant-svc
 * keeps.
 *
 * <p>Erasing twice finds nothing the second time. The evidence's event id is derived from the
 * erasure's and this service's name, so a redelivered event is recorded once however often it is
 * handled. A payload that is not an erasure is skipped; a failure to erase is thrown, so the record
 * is read again rather than acknowledged with the data still there.
 */
@ApplicationScoped
public class TenantDataErasureHandler {

  /** The event tenant-svc publishes when a tenant's data is due for erasure. */
  public static final String DUE = "TenantDataErasureDue";

  /** The event each service publishes with what it erased. */
  public static final String ERASED = "TenantDataErased";

  private static final Logger LOG = System.getLogger(TenantDataErasureHandler.class.getName());

  @Inject TenantDataRepository data;
  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.kafka.topics.tenant-data-erased")
  Optional<String> topicCfg;

  /**
   * Handles one record.
   *
   * @param json the {@code TenantDataErasureDue} payload: {@code eventId}, {@code tenantId}
   */
  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject o = reader.readObject();
      if (!DUE.equals(o.getString("eventType", ""))) {
        LOG.log(Level.WARNING, "Not a " + DUE + " record; skipped");
        return;
      }
      eventId = UUID.fromString(o.getString("eventId"));
      tenantId = UUID.fromString(o.getString("tenantId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed " + DUE + " payload skipped: " + e.getMessage());
      return;
    }
    String service = settings.serviceName();
    Map<String, Integer> counts =
        data.erase(
            tenantId,
            c ->
                new OutboxRow(
                    ERASED,
                    topic(service),
                    tenantId,
                    tenantId,
                    payload(eventId, tenantId, service, c)));
    LOG.log(
        Level.INFO,
        "Tenant {0} erased from {1}: {2} rows",
        tenantId,
        service,
        counts.values().stream().mapToInt(Integer::intValue).sum());
  }

  String topic(String service) {
    return topicCfg.orElseGet(
        () ->
            "storeql."
                + service.toLowerCase(Locale.ROOT).replaceFirst("-svc$", "")
                + ".tenant-data-erased");
  }

  static String payload(
      UUID erasureEventId, UUID tenantId, String service, Map<String, Integer> counts) {
    JsonObjectBuilder tables = Json.createObjectBuilder();
    counts.forEach(tables::add);
    return Json.createObjectBuilder()
        .add("eventId", Ids.derived(erasureEventId, "erased:" + service).toString())
        .add("eventType", ERASED)
        .add("tenantId", tenantId.toString())
        .add("aggregateId", tenantId.toString())
        .add("occurredAt", Instant.now().toString())
        .add("service", service)
        .add("erasureEventId", erasureEventId.toString())
        .add("rows", counts.values().stream().mapToInt(Integer::intValue).sum())
        .add("tables", tables)
        .build()
        .toString();
  }
}
