package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.AgeRestrictionRule;
import com.shelfj.product.domain.Domain.Allergen;
import com.shelfj.product.domain.Domain.VariantAllergen;
import com.shelfj.product.domain.Domain.VariantCompliance;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Allergen declarations, age-restriction rules, and the compliance fields on a variant.
 *
 * <p>Separate from {@link ProductRepository} for the reason {@link ItemCrossReferenceRepository}
 * is: self-contained, no outbox events, no coupling to another aggregate. It is also the part of
 * product-svc where a wrong answer is a safety incident rather than a bad report, which is worth
 * keeping visible in one file.
 */
@ApplicationScoped
public class ComplianceRepository extends BaseJdbcRepository {

  // ── the regulated list ──────────────────────────────────────────────────────

  public List<Allergen> listAllergens() {
    return query(
        "SELECT code, name, detail, regulation FROM allergens ORDER BY code",
        ps -> {},
        (rs) -> new Allergen(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
        "list allergens");
  }

  // ── declarations ────────────────────────────────────────────────────────────

  public List<VariantAllergen> listVariantAllergens(UUID tenantId, UUID variantId) {
    return query(
        "SELECT tenant_id, variant_id, allergen_code, presence, declared_by, declared_at"
            + " FROM variant_allergens WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY allergen_code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ComplianceRepository::mapDeclaration,
        "list variant allergens");
  }

  /**
   * Replaces a variant's whole declaration in one transaction, and sets its status in the same one.
   *
   * <p>Replace rather than merge: a declaration is a complete statement about a product, so
   * removing an allergen has to be expressible. Merging would make "we were wrong, it has no
   * celery" impossible to say.
   *
   * <p>The status write is the point of doing this in a transaction. A variant left saying DECLARED
   * with its rows deleted would advertise itself as free from everything.
   */
  public void replaceDeclaration(
      UUID tenantId, UUID variantId, List<VariantAllergen> rows, String status) {
    inTx(
        c -> {
          try (var del =
              c.prepareStatement(
                  "DELETE FROM variant_allergens WHERE tenant_id = ? AND variant_id = ?")) {
            del.setObject(1, tenantId);
            del.setObject(2, variantId);
            del.executeUpdate();
          }
          if (!rows.isEmpty()) {
            try (var ins =
                c.prepareStatement(
                    "INSERT INTO variant_allergens"
                        + " (tenant_id, variant_id, allergen_code, presence, declared_by)"
                        + " VALUES (?,?,?,?,?)")) {
              for (VariantAllergen r : rows) {
                ins.setObject(1, tenantId);
                ins.setObject(2, variantId);
                ins.setString(3, r.allergenCode());
                ins.setString(4, r.presence());
                ins.setObject(5, r.declaredBy());
                ins.addBatch();
              }
              ins.executeBatch();
            }
          }
          try (var st =
              c.prepareStatement(
                  "UPDATE product_variants SET allergen_status = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            st.setString(1, status);
            st.setObject(2, tenantId);
            st.setObject(3, variantId);
            st.executeUpdate();
          }
          return null;
        },
        "replace allergen declaration");
  }

  /** Every variant carrying one allergen — the query a recall and an allergy search both run. */
  public List<UUID> variantsWithAllergen(UUID tenantId, String allergenCode, String presence) {
    String sql =
        "SELECT variant_id FROM variant_allergens WHERE tenant_id = ? AND allergen_code = ?"
            + (presence == null ? "" : " AND presence = ?")
            + " ORDER BY variant_id";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, allergenCode);
          if (presence != null) {
            ps.setString(3, presence);
          }
        },
        rs -> (UUID) rs.getObject(1),
        "variants with allergen");
  }

  /**
   * Food products nobody has declared yet.
   *
   * <p>The list this exists to produce is the one a food business is asked for when it is
   * inspected, and the one that says which shelves cannot lawfully be filled yet.
   */
  public List<UUID> undeclaredVariants(UUID tenantId, int limit) {
    return query(
        "SELECT id FROM product_variants"
            + " WHERE tenant_id = ? AND allergen_status = 'UNDECLARED'"
            + " ORDER BY created_at LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        rs -> (UUID) rs.getObject(1),
        "undeclared variants");
  }

  /**
   * Marks a variant as food whose allergens nobody has declared yet.
   *
   * <p>Only moves NOT_APPLICABLE. An item already DECLARED keeps its declaration: marking it as
   * food again must never turn a statement someone made back into an unknown.
   */
  public void markFoodUndeclared(UUID tenantId, UUID variantId) {
    exec(
        "UPDATE product_variants SET allergen_status = 'UNDECLARED', updated_at = now()"
            + " WHERE tenant_id = ? AND id = ? AND allergen_status = 'NOT_APPLICABLE'",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        "mark food undeclared");
  }

  // ── the variant's own compliance fields ─────────────────────────────────────

  public VariantCompliance findCompliance(UUID tenantId, UUID variantId) {
    var rows =
        query(
            "SELECT id, country_of_origin, origin_detail, restriction_category, allergen_status,"
                + " ingredients, sold_by, net_content, net_content_uom, tare_weight, catch_weight"
                + " FROM product_variants WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            ComplianceRepository::mapCompliance,
            "find variant compliance");
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Returns false when the variant does not belong to this tenant, rather than silently no-op. */
  public boolean updateCompliance(UUID tenantId, VariantCompliance v) {
    return inTx(
        c -> {
          try (var st =
              c.prepareStatement(
                  "UPDATE product_variants SET country_of_origin = ?, origin_detail = ?,"
                      + " restriction_category = ?, ingredients = ?, sold_by = ?,"
                      + " net_content = ?, net_content_uom = ?, tare_weight = ?,"
                      + " catch_weight = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            st.setString(1, v.countryOfOrigin());
            st.setString(2, v.originDetail());
            st.setString(3, v.restrictionCategory());
            st.setString(4, v.ingredients());
            st.setString(5, v.soldBy());
            st.setBigDecimal(6, v.netContent());
            st.setString(7, v.netContentUom());
            st.setBigDecimal(8, v.tareWeight());
            st.setBoolean(9, v.catchWeight());
            st.setObject(10, tenantId);
            st.setObject(11, v.variantId());
            return st.executeUpdate() > 0;
          }
        },
        "update variant compliance");
  }

  // ── age restriction ─────────────────────────────────────────────────────────

  /**
   * The minimum age for a category in a country: the tenant's own rule if it has one, else the
   * statutory default.
   *
   * <p>One query rather than two round trips, because the till asks this on every restricted line
   * scanned and a second hop at the till is a second thing that can be slow when a queue is
   * waiting.
   */
  public Integer minimumAge(UUID tenantId, String country, String category) {
    var rows =
        query(
            "SELECT minimum_age FROM ("
                + "  SELECT minimum_age, 0 AS rank FROM tenant_age_restriction_rules"
                + "    WHERE tenant_id = ? AND country = ? AND category = ?"
                + "  UNION ALL"
                + "  SELECT minimum_age, 1 AS rank FROM age_restriction_rules"
                + "    WHERE country = ? AND category = ?"
                + ") r ORDER BY rank LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, country);
              ps.setString(3, category);
              ps.setString(4, country);
              ps.setString(5, category);
            },
            rs -> rs.getInt(1),
            "minimum age");
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Every rule in force for a country, a tenant override shadowing the statutory default.
   *
   * <p>A left join rather than a ranked union: the tenant row wins where it exists, and the second
   * arm picks up categories a tenant restricts that the statute does not mention at all — a
   * business is free to set an age on something the law leaves open.
   */
  public List<AgeRestrictionRule> rulesFor(UUID tenantId, String country) {
    return query(
        "SELECT d.category, COALESCE(t.minimum_age, d.minimum_age),"
            + " COALESCE(t.reason, d.note), t.tenant_id"
            + " FROM age_restriction_rules d"
            + " LEFT JOIN tenant_age_restriction_rules t"
            + "   ON t.tenant_id = ? AND t.country = d.country AND t.category = d.category"
            + " WHERE d.country = ?"
            + " UNION ALL"
            + " SELECT t.category, t.minimum_age, t.reason, t.tenant_id"
            + " FROM tenant_age_restriction_rules t"
            + " WHERE t.tenant_id = ? AND t.country = ?"
            + "   AND NOT EXISTS (SELECT 1 FROM age_restriction_rules d"
            + "                    WHERE d.country = t.country AND d.category = t.category)"
            + " ORDER BY 1",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, country);
          ps.setObject(3, tenantId);
          ps.setString(4, country);
        },
        rs ->
            new AgeRestrictionRule(
                (UUID) rs.getObject(4), country, rs.getString(1), rs.getInt(2), rs.getString(3)),
        "rules for country");
  }

  public void upsertTenantRule(AgeRestrictionRule rule, UUID setBy) {
    exec(
        "INSERT INTO tenant_age_restriction_rules"
            + " (tenant_id, country, category, minimum_age, reason, set_by)"
            + " VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, country, category) DO UPDATE SET"
            + " minimum_age = EXCLUDED.minimum_age, reason = EXCLUDED.reason,"
            + " set_by = EXCLUDED.set_by, set_at = now()",
        ps -> {
          ps.setObject(1, rule.tenantId());
          ps.setString(2, rule.country());
          ps.setString(3, rule.category());
          ps.setInt(4, rule.minimumAge());
          ps.setString(5, rule.note());
          ps.setObject(6, setBy);
        },
        "upsert tenant age rule");
  }

  // ── mappers ─────────────────────────────────────────────────────────────────

  private static VariantAllergen mapDeclaration(ResultSet rs) throws SQLException {
    return new VariantAllergen(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        rs.getString(3),
        rs.getString(4),
        (UUID) rs.getObject(5),
        rs.getObject(6, java.time.OffsetDateTime.class).toInstant());
  }

  private static VariantCompliance mapCompliance(ResultSet rs) throws SQLException {
    return new VariantCompliance(
        (UUID) rs.getObject(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getBigDecimal(8),
        rs.getString(9),
        rs.getBigDecimal(10),
        rs.getBoolean(11));
  }
}
