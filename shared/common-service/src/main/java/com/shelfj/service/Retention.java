package com.shelfj.service;

import com.shelfj.ids.Ids;
import com.shelfj.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A business's retention schedule as tenant-svc keeps it (21.16): the period set per class of data,
 * and the holds in force. Every service that purges asks this before it deletes anything, and a
 * purge that cannot read the schedule does not run &mdash; nothing is deleted on a guess. Read
 * afresh for every purge: purges are rare, and a hold placed a moment ago must stop the next one.
 */
@ApplicationScoped
public class Retention {

  private static final Logger LOG = System.getLogger(Retention.class.getName());

  public static final String TRANSACTIONS = "TRANSACTIONS";
  public static final String ORDER_PERSONAL_DATA = "ORDER_PERSONAL_DATA";
  public static final String CUSTOMER_RECORDS = "CUSTOMER_RECORDS";
  public static final String NOTIFICATION_LOG = "NOTIFICATION_LOG";

  /** A hold in force: on one class or every class; on one customer, one order, or everything. */
  public record Hold(String dataClass, String subjectKind, UUID subjectId) {

    boolean covers(String dataClass) {
      return this.dataClass == null || this.dataClass.equals(dataClass);
    }
  }

  /** The schedule as it stands: what is set, and what is held. */
  public record Sheet(Map<String, Integer> periods, List<Hold> holds) {

    /** The period set for a class, or empty when the business has not decided. */
    public Optional<Integer> periodDays(String dataClass) {
      return Optional.ofNullable(periods.get(dataClass));
    }

    /** Whether a hold stops every purge of the class. */
    public boolean classHeld(String dataClass) {
      return holds.stream().anyMatch(h -> "ALL".equals(h.subjectKind()) && h.covers(dataClass));
    }

    /** The subjects of a kind held for the class, e.g. the customers a purge must leave alone. */
    public Set<UUID> heldSubjects(String dataClass, String subjectKind) {
      Set<UUID> out = new HashSet<>();
      for (Hold h : holds) {
        if (subjectKind.equals(h.subjectKind()) && h.covers(dataClass) && h.subjectId() != null) {
          out.add(h.subjectId());
        }
      }
      return out;
    }
  }

  /** What a purge did: the rows it deleted or anonymised, and the subjects a hold kept from it. */
  public record Counts(int rowsAffected, int heldSkipped) {}

  /** One purge as a service ran it: the response of a sweep, and what the register records. */
  public record Run(
      String dataClass,
      String cutoff,
      int rowsAffected,
      int heldSkipped,
      String startedAt,
      String finishedAt) {}

  /**
   * A service's purge of one class for one tenant: everything older than the cutoff that no hold
   * keeps, with the run's announcement written in the same transaction.
   */
  @FunctionalInterface
  public interface Purge {
    /**
     * @param cutoff rows from before this moment are due
     * @param classHeld whether a hold stops the whole class; then nothing is purged and every
     *     candidate counts as held
     * @param sheet the schedule, for the held subjects
     * @param runPayload builds the {@code RetentionRunCompleted} payload from the counts, to be
     *     written to the outbox in the purge's own transaction
     */
    Counts apply(
        Instant cutoff, boolean classHeld, Sheet sheet, Function<Counts, String> runPayload);
  }

  /**
   * Runs a purge as the schedule says: nothing when the business has set no period for the class;
   * otherwise everything older than the period, keeping what is held, and announcing the run.
   *
   * @param service the purging service's name, as the register shows it
   * @return the run, or empty when no period is set
   * @throws ApiException 503 {@code RETENTION_UNAVAILABLE} when the schedule cannot be read
   */
  public Optional<Run> purge(UUID tenantId, String service, String dataClass, Purge purge) {
    Sheet sheet = sheet(tenantId);
    Optional<Integer> days = sheet.periodDays(dataClass);
    if (days.isEmpty()) {
      return Optional.empty();
    }
    Instant started = clock.instant();
    Instant cutoff = started.minus(Duration.ofDays(days.get()));
    Instant[] finished = new Instant[1];
    Counts counts =
        purge.apply(
            cutoff,
            sheet.classHeld(dataClass),
            sheet,
            c -> {
              finished[0] = clock.instant();
              return runCompleted(
                  service,
                  tenantId,
                  dataClass,
                  cutoff,
                  c.rowsAffected(),
                  c.heldSkipped(),
                  started,
                  finished[0]);
            });
    if (finished[0] == null) {
      finished[0] = clock.instant();
    }
    return Optional.of(
        new Run(
            dataClass,
            cutoff.toString(),
            counts.rowsAffected(),
            counts.heldSkipped(),
            started.toString(),
            finished[0].toString()));
  }

  /**
   * The outbox row announcing a run on the purging service's own topic, from the counts a purge
   * reached: what every purger hands its repository.
   */
  public static Function<Counts, OutboxRow> announce(
      String topic, UUID tenantId, Function<Counts, String> payload) {
    return counts ->
        new OutboxRow("RetentionRunCompleted", topic, tenantId, tenantId, payload.apply(counts));
  }

  /** The {@code RetentionRunCompleted} payload a purger announces, with a fresh event id. */
  public static String runCompleted(
      String service,
      UUID tenantId,
      String dataClass,
      Instant cutoff,
      int rowsAffected,
      int heldSkipped,
      Instant startedAt,
      Instant finishedAt) {
    return Json.createObjectBuilder()
        .add("eventId", Ids.newId().toString())
        .add("eventType", "RetentionRunCompleted")
        .add("tenantId", tenantId.toString())
        .add("aggregateId", tenantId.toString())
        .add("occurredAt", finishedAt.toString())
        .add("service", service)
        .add("dataClass", dataClass)
        .add("cutoff", cutoff.toString())
        .add("rowsAffected", rowsAffected)
        .add("heldSkipped", heldSkipped)
        .add("startedAt", startedAt.toString())
        .add("finishedAt", finishedAt.toString())
        .build()
        .toString();
  }

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "shelfj.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private Clock clock = Clock.systemUTC();
  private Function<UUID, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch = tenantId -> client.body(tenantId, "/admin/tenant/retention", Map.of());
  }

  /** For tests: a stand-in fetch and a clock. */
  static Retention forTest(Function<UUID, Optional<String>> fetch, Clock clock) {
    Retention r = new Retention();
    r.fetch = fetch;
    r.clock = clock;
    return r;
  }

  /**
   * The schedule for a tenant.
   *
   * @throws ApiException 503 {@code RETENTION_UNAVAILABLE} when tenant-svc cannot answer: a purge
   *     with no schedule to obey does not run
   */
  public Sheet sheet(UUID tenantId) {
    return fetch
        .apply(tenantId)
        .flatMap(Retention::parse)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "RETENTION_UNAVAILABLE",
                    "the retention schedule could not be read from tenant-svc; nothing was"
                        + " purged, try again",
                    List.of()));
  }

  /**
   * Reads a {@code GET /admin/tenant/retention} response; empty when it cannot be read whole,
   * because a schedule with one class silently missing would purge what it should keep.
   */
  static Optional<Sheet> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      Map<String, Integer> periods = new HashMap<>();
      for (JsonValue value : data.getJsonArray("classes")) {
        JsonObject c = value.asJsonObject();
        if (c.containsKey("periodDays") && !c.isNull("periodDays")) {
          periods.put(c.getString("code"), c.getInt("periodDays"));
        }
      }
      List<Hold> holds = new ArrayList<>();
      for (JsonValue value : data.getJsonArray("holds")) {
        JsonObject h = value.asJsonObject();
        holds.add(
            new Hold(
                h.containsKey("dataClass") && !h.isNull("dataClass")
                    ? h.getString("dataClass")
                    : null,
                h.getString("subjectKind"),
                h.containsKey("subjectId") && !h.isNull("subjectId")
                    ? UUID.fromString(h.getString("subjectId"))
                    : null));
      }
      return Optional.of(new Sheet(Map.copyOf(periods), List.copyOf(holds)));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable retention schedule: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
