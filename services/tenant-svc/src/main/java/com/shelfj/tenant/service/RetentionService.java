package com.shelfj.tenant.service;

import com.shelfj.ids.Ids;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Retention;
import com.shelfj.tenant.domain.Retention.ClassStatus;
import com.shelfj.tenant.domain.Retention.DataClass;
import com.shelfj.tenant.domain.Retention.Floor;
import com.shelfj.tenant.domain.Retention.Hold;
import com.shelfj.tenant.domain.Retention.Run;
import com.shelfj.tenant.domain.Retention.Schedule;
import com.shelfj.tenant.domain.Retention.SubjectKind;
import com.shelfj.tenant.repo.RetentionRepository;
import com.shelfj.tenant.repo.TenantRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retention schedules (21.16): what the law of the countries a business trades in requires, what
 * the business has set, the holds that stop a purge, and the register of purges run.
 */
@ApplicationScoped
public class RetentionService {

  private static final Logger LOG = System.getLogger(RetentionService.class.getName());

  @Inject RetentionRepository repo;
  @Inject TenantRepository tenants;

  /** The schedule as the business sees it. */
  public record Sheet(
      String country, Set<String> countries, List<ClassStatus> classes, List<Hold> holds) {}

  /**
   * The schedule: every class with the highest floor among the countries the business trades in
   * &mdash; its own and its stores&rsquo; &mdash; and the period it has set, if any.
   *
   * @throws ApiException 404 {@code TENANT_NOT_FOUND}
   */
  public Sheet sheet(UUID tenantId) {
    Tenant tenant =
        tenants
            .findTenant(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such tenant"));
    Set<String> countries = countriesTrading(tenant);
    Map<String, Floor> floors = highestFloors(countries);
    Map<String, Schedule> set =
        repo.latestSchedules(tenantId).stream()
            .collect(Collectors.toMap(Schedule::dataClass, s -> s));
    List<ClassStatus> classes = new ArrayList<>();
    for (DataClass c : repo.classes()) {
      classes.add(new ClassStatus(c, floors.get(c.code()), set.get(c.code())));
    }
    return new Sheet(tenant.country(), countries, classes, repo.holds(tenantId, true));
  }

  /**
   * Sets a class's period: a longer one than the law's floor is allowed, a shorter one is not.
   *
   * @throws ApiException 400 {@code RETENTION_CLASS_UNKNOWN}, {@code RETENTION_PERIOD_INVALID} or
   *     {@code RETENTION_BELOW_LEGAL_MINIMUM}; 404 {@code TENANT_NOT_FOUND}
   */
  public ClassStatus set(UUID tenantId, String code, int periodDays, UUID actor) {
    DataClass dataClass = requireClass(code);
    Tenant tenant =
        tenants
            .findTenant(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such tenant"));
    Floor floor = highestFloors(countriesTrading(tenant)).get(dataClass.code());
    Retention.requireLawful(periodDays, floor);
    Schedule schedule =
        repo.insertSchedule(
            new Schedule(
                Ids.newId(), tenantId, dataClass.code(), periodDays, actor, Instant.now()));
    return new ClassStatus(dataClass, floor, schedule);
  }

  /**
   * Places a hold: on a class or every class, for one customer, one order, or everything.
   *
   * @throws ApiException 400 {@code RETENTION_CLASS_UNKNOWN} or {@code RETENTION_HOLD_SUBJECT}
   */
  public Hold place(
      UUID tenantId,
      UUID actor,
      String dataClass,
      SubjectKind kind,
      UUID subjectId,
      String reason) {
    String code = dataClass == null || dataClass.isBlank() ? null : requireClass(dataClass).code();
    if ((kind == SubjectKind.ALL) != (subjectId == null)) {
      throw ApiException.badRequest(
          "RETENTION_HOLD_SUBJECT",
          "A hold on ALL names no subject; a hold on a CUSTOMER or an ORDER names its id");
    }
    return repo.insertHold(
        new Hold(
            Ids.newId(),
            tenantId,
            code,
            kind,
            subjectId,
            reason.trim(),
            actor,
            Instant.now(),
            null,
            null,
            null));
  }

  public Hold release(UUID tenantId, UUID id, UUID actor, String reason) {
    return repo.release(tenantId, id, actor, reason.trim());
  }

  public List<Hold> holds(UUID tenantId, boolean activeOnly) {
    return repo.holds(tenantId, activeOnly);
  }

  public Cursor.Page<Run> runs(UUID tenantId, String after, Integer limit) {
    int lim = Cursor.clampLimit(limit);
    var rows = repo.runs(tenantId, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, r -> r.finishedAt() + "|" + r.id());
  }

  /**
   * Records a purge a service announced ({@code RetentionRunCompleted}), once per event. A
   * malformed event, or one naming a class this service does not know, is skipped with a warning.
   *
   * @return whether the run was recorded now
   */
  public boolean recordRun(String json) {
    Run run;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject o = reader.readObject();
      run =
          new Run(
              UUID.fromString(o.getString("eventId")),
              UUID.fromString(o.getString("tenantId")),
              o.getString("service"),
              o.getString("dataClass"),
              Instant.parse(o.getString("cutoff")),
              o.getInt("rowsAffected"),
              o.getInt("heldSkipped"),
              Instant.parse(o.getString("startedAt")),
              Instant.parse(o.getString("finishedAt")),
              null);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RetentionRunCompleted payload skipped: " + e.getMessage());
      return false;
    }
    if (repo.classes().stream().noneMatch(c -> c.code().equals(run.dataClass()))) {
      LOG.log(Level.WARNING, "RetentionRunCompleted names unknown class {0}", run.dataClass());
      return false;
    }
    if (run.rowsAffected() < 0 || run.heldSkipped() < 0) {
      LOG.log(Level.WARNING, "RetentionRunCompleted with negative counts skipped");
      return false;
    }
    return repo.recordRunOnce(run);
  }

  private DataClass requireClass(String code) {
    String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    return repo.classes().stream()
        .filter(c -> c.code().equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                ApiException.badRequest(
                    "RETENTION_CLASS_UNKNOWN",
                    "dataClass must be one of "
                        + repo.classes().stream()
                            .map(DataClass::code)
                            .collect(Collectors.joining(", "))));
  }

  /** The business's own country and every store's: a store across a border trades under its. */
  private Set<String> countriesTrading(Tenant tenant) {
    Set<String> out = new TreeSet<>();
    if (tenant.country() != null && !tenant.country().isBlank()) {
      out.add(tenant.country().trim().toUpperCase(Locale.ROOT));
    }
    for (Store store : tenants.listStores(tenant.id())) {
      if (store.country() != null && !store.country().isBlank()) {
        out.add(store.country().trim().toUpperCase(Locale.ROOT));
      }
    }
    return out;
  }

  private Map<String, Floor> highestFloors(Set<String> countries) {
    Map<String, List<Floor>> byClass =
        repo.floorsFor(countries).stream().collect(Collectors.groupingBy(Floor::dataClass));
    Map<String, Floor> out = new java.util.HashMap<>();
    byClass.forEach(
        (code, floors) -> {
          Optional<Floor> highest = Floor.highest(floors);
          highest.ifPresent(f -> out.put(code, f));
        });
    return out;
  }
}
