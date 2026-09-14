package com.shelfj.tenant.repo;

import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.tenant.domain.Domain.LegalObligation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;

/**
 * The jurisdiction reference data (V9): which obligations reach a country, directly or through a
 * regime it belongs to. Platform-wide, so nothing here filters by tenant — no row belongs to one.
 */
@ApplicationScoped
public class ObligationRepository extends BaseJdbcRepository {

  /**
   * Every obligation that reaches {@code country} at some time: its own, and each regime's with the
   * window narrowed to the membership. A window that closes before it opens is returned as such,
   * for the caller to drop.
   */
  public List<LegalObligation> forCountry(String country) {
    return query(
        "SELECT o.code, o.scope_kind, o.scope, o.citation, o.summary,"
            + " GREATEST(o.effective_from, COALESCE(m.member_from, o.effective_from)) AS eff_from,"
            + " CASE WHEN m.member_to IS NULL THEN o.effective_to"
            + "      WHEN o.effective_to IS NULL THEN m.member_to"
            + "      ELSE LEAST(o.effective_to, m.member_to) END AS eff_to"
            + " FROM legal_obligations o"
            + " LEFT JOIN jurisdiction_members m"
            + "   ON o.scope_kind = 'REGIME' AND m.regime_code = o.scope AND m.country = ?"
            + " WHERE (o.scope_kind = 'COUNTRY' AND o.scope = ?)"
            + "    OR (o.scope_kind = 'REGIME' AND m.country IS NOT NULL)"
            + " ORDER BY eff_from, o.code",
        ps -> {
          ps.setString(1, country);
          ps.setString(2, country);
        },
        rs ->
            new LegalObligation(
                rs.getString("code"),
                rs.getString("scope_kind"),
                rs.getString("scope"),
                rs.getObject("eff_from", LocalDate.class),
                rs.getObject("eff_to", LocalDate.class),
                rs.getString("citation"),
                rs.getString("summary")),
        "legal obligations for a country");
  }
}
