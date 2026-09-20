package com.shelfj.tenant.repo;

import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.tenant.domain.Commission;
import com.shelfj.tenant.domain.Commission.Assignment;
import com.shelfj.tenant.domain.Commission.Band;
import com.shelfj.tenant.domain.Commission.Scheme;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Commission schemes and who is on them (store operations & workforce).
 *
 * <p>A scheme and its bands are written in one transaction, because a scheme with no bands earns
 * nothing and a band with no scheme is unreachable — a half-written arrangement is worse than none.
 *
 * <p>A correction supersedes: the old version is pointed at the new one and the new one back at it,
 * under a deferrable foreign key so both rows can be written in either order. Nothing is ever
 * updated in place, so commission earned last month cannot be re-rated this month.
 */
@ApplicationScoped
public class CommissionRepository extends BaseJdbcRepository {

  private static final String SCHEME_COLUMNS =
      "id, tenant_id, name, basis, currency, status, note, supersedes, superseded_by, created_at,"
          + " created_by";

  private static final String BAND_COLUMNS = "id, scheme_id, threshold_from, rate";

  private static final String ASSIGNMENT_COLUMNS =
      "id, tenant_id, user_id, scheme_id, effective_from, note, created_at, created_by";

  private static final String INSERT_SCHEME =
      "INSERT INTO commission_schemes (id, tenant_id, name, basis, currency, status, note,"
          + " supersedes, superseded_by, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)";

  private static final String INSERT_BAND =
      "INSERT INTO commission_scheme_bands (id, tenant_id, scheme_id, threshold_from, rate)"
          + " VALUES (?,?,?,?,?)";

  /**
   * Writes a scheme with its bands, and closes the version it replaces.
   *
   * @param supersededId the version this one replaces, or null for a new arrangement
   */
  public Scheme create(Scheme scheme, UUID supersededId) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(INSERT_SCHEME)) {
            bindScheme(ps, scheme);
            ps.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_BAND)) {
            for (Band b : scheme.bands()) {
              ps.setObject(1, b.id());
              ps.setObject(2, scheme.tenantId());
              ps.setObject(3, scheme.id());
              ps.setBigDecimal(4, b.thresholdFrom());
              ps.setBigDecimal(5, b.rate());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          if (supersededId != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE commission_schemes SET superseded_by = ?, status = ?"
                        + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL")) {
              ps.setObject(1, scheme.id());
              ps.setString(2, Commission.WITHDRAWN);
              ps.setObject(3, scheme.tenantId());
              ps.setObject(4, supersededId);
              ps.executeUpdate();
            }
          }
          return scheme;
        },
        "record a commission scheme");
  }

  /** Withdraws a scheme: nobody new earns under it, and what was earned under it stands. */
  public boolean withdraw(UUID tenantId, UUID schemeId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE commission_schemes SET status = ? WHERE tenant_id = ? AND id = ?"
                      + " AND status = ? AND superseded_by IS NULL")) {
            ps.setString(1, Commission.WITHDRAWN);
            ps.setObject(2, tenantId);
            ps.setObject(3, schemeId);
            ps.setString(4, Commission.ACTIVE);
            return ps.executeUpdate() > 0;
          }
        },
        "withdraw a commission scheme");
  }

  /** The tenant's schemes, newest first, each with its bands. */
  public List<Scheme> schemes(UUID tenantId, boolean activeOnly) {
    String sql =
        activeOnly
            ? "SELECT "
                + SCHEME_COLUMNS
                + " FROM commission_schemes WHERE tenant_id = ?"
                + " AND status = 'ACTIVE' AND superseded_by IS NULL ORDER BY created_at DESC"
            : "SELECT "
                + SCHEME_COLUMNS
                + " FROM commission_schemes WHERE tenant_id = ?"
                + " ORDER BY created_at DESC";
    List<Scheme> bare =
        query(
            sql, ps -> ps.setObject(1, tenantId), CommissionRepository::mapScheme, "list schemes");
    return withBands(tenantId, bare);
  }

  /** One scheme with its bands, whatever its status: a statement must be able to explain itself. */
  public Optional<Scheme> scheme(UUID tenantId, UUID schemeId) {
    List<Scheme> found =
        query(
            "SELECT " + SCHEME_COLUMNS + " FROM commission_schemes WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, schemeId);
            },
            CommissionRepository::mapScheme,
            "read a commission scheme");
    return withBands(tenantId, found).stream().findFirst();
  }

  /** Puts somebody on a scheme from a day, or takes them off it with a null scheme. */
  public Assignment assign(Assignment a) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO staff_commission_schemes (id, tenant_id, user_id, scheme_id,"
                      + " effective_from, note, created_at, created_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.userId());
            ps.setObject(4, a.schemeId());
            ps.setObject(5, a.effectiveFrom());
            ps.setString(6, a.note());
            ps.setObject(7, a.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(8, a.createdBy());
            ps.executeUpdate();
          }
          return a;
        },
        "assign a commission scheme");
  }

  /** A person's arrangements, newest first. */
  public List<Assignment> assignments(UUID tenantId, UUID userId) {
    return query(
        "SELECT "
            + ASSIGNMENT_COLUMNS
            + " FROM staff_commission_schemes"
            + " WHERE tenant_id = ? AND user_id = ? ORDER BY effective_from DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
        },
        CommissionRepository::mapAssignment,
        "list commission assignments");
  }

  /**
   * The arrangement in force on a day, by person.
   *
   * <p>Folded from {@link #assignmentsUpTo} rather than read again: two queries that both meant
   * "every assignment up to a day" would eventually disagree, and this one decides who is on a
   * scheme while the other decides what a period earns.
   */
  public Map<UUID, UUID> schemesOn(UUID tenantId, LocalDate day) {
    Map<UUID, UUID> byPerson = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<Assignment>> person : assignmentsUpTo(tenantId, day).entrySet()) {
      UUID scheme = Commission.schemeOn(person.getValue(), day);
      if (scheme != null) byPerson.put(person.getKey(), scheme);
    }
    return byPerson;
  }

  /**
   * Every arrangement written on or before a day, by person and newest last.
   *
   * <p>One read for a whole statement: rating a month of sales for forty people cannot be forty
   * round trips, and the rule needs each person's whole timeline to cut the period into segments.
   */
  public Map<UUID, List<Assignment>> assignmentsUpTo(UUID tenantId, LocalDate day) {
    Map<UUID, List<Assignment>> byPerson = new LinkedHashMap<>();
    for (Assignment a :
        query(
            "SELECT "
                + ASSIGNMENT_COLUMNS
                + " FROM staff_commission_schemes"
                + " WHERE tenant_id = ? AND effective_from <= ? ORDER BY user_id, effective_from",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, day);
            },
            CommissionRepository::mapAssignment,
            "read commission arrangements for a period")) {
      byPerson.computeIfAbsent(a.userId(), k -> new ArrayList<>()).add(a);
    }
    return byPerson;
  }

  /** Whether the tenant has this person on its staff at all — at any store. */
  public boolean isStaff(UUID tenantId, UUID userId) {
    return !query(
            "SELECT 1 AS present FROM staff_assignments WHERE tenant_id = ? AND user_id = ? LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs -> rs.getInt("present"),
            "a staff assignment")
        .isEmpty();
  }

  private List<Scheme> withBands(UUID tenantId, List<Scheme> schemes) {
    if (schemes.isEmpty()) return schemes;
    Map<UUID, List<Band>> bands = new LinkedHashMap<>();
    for (Scheme s : schemes) {
      bands.put(
          s.id(),
          query(
              "SELECT "
                  + BAND_COLUMNS
                  + " FROM commission_scheme_bands"
                  + " WHERE tenant_id = ? AND scheme_id = ? ORDER BY threshold_from",
              ps -> {
                ps.setObject(1, tenantId);
                ps.setObject(2, s.id());
              },
              CommissionRepository::mapBand,
              "read commission bands"));
    }
    List<Scheme> out = new ArrayList<>(schemes.size());
    for (Scheme s : schemes) {
      out.add(
          new Scheme(
              s.id(),
              s.tenantId(),
              s.name(),
              s.basis(),
              s.currency(),
              s.status(),
              s.note(),
              s.supersedes(),
              s.supersededBy(),
              s.createdAt(),
              s.createdBy(),
              bands.getOrDefault(s.id(), List.of())));
    }
    return List.copyOf(out);
  }

  private static void bindScheme(PreparedStatement ps, Scheme s) throws SQLException {
    ps.setObject(1, s.id());
    ps.setObject(2, s.tenantId());
    ps.setString(3, s.name());
    ps.setString(4, s.basis());
    ps.setString(5, s.currency());
    ps.setString(6, s.status());
    ps.setString(7, s.note());
    ps.setObject(8, s.supersedes());
    ps.setObject(9, s.supersededBy());
    ps.setObject(10, s.createdAt().atOffset(ZoneOffset.UTC));
    ps.setObject(11, s.createdBy());
  }

  private static Scheme mapScheme(ResultSet rs) throws SQLException {
    return new Scheme(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("basis"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("note"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class),
        List.of());
  }

  private static Band mapBand(ResultSet rs) throws SQLException {
    return new Band(
        rs.getObject("id", UUID.class),
        rs.getObject("scheme_id", UUID.class),
        rs.getBigDecimal("threshold_from"),
        rs.getBigDecimal("rate"));
  }

  private static Assignment mapAssignment(ResultSet rs) throws SQLException {
    return new Assignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("scheme_id", UUID.class),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("note"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class));
  }

  /**
   * The one race the database decides, named so a caller can answer it properly.
   *
   * <p>Without this it is a generic 500: the default mapping knows nothing of this service's
   * constraints, and a manager told "database error" when the real answer is "an arrangement
   * already starts that day" would try again and again.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_commission_assignment_day")) {
      return ApiException.conflict(
          "COMMISSION_ARRANGEMENT_EXISTS",
          "an arrangement already starts on that day for that person");
    }
    return super.handleTxSqlException(what, e);
  }
}
