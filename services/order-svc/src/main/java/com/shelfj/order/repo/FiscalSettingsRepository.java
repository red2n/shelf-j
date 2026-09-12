package com.shelfj.order.repo;

import com.shelfj.order.domain.Domain.FiscalStoreSettings;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The fiscal regime each store trades under (18.5). One row per store; no row means NONE. */
@ApplicationScoped
public class FiscalSettingsRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "tenant_id, store_id, regime, tax_registration_number, certificate_number,"
          + " series_validation_code, updated_at, updated_by";

  /**
   * The settings a store is under.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @return the row, or empty when the store has never been placed under a regime
   */
  public Optional<FiscalStoreSettings> find(UUID tenantId, UUID storeId) {
    List<FiscalStoreSettings> rows =
        query(
            "SELECT "
                + COLUMNS
                + " FROM fiscal_store_settings WHERE tenant_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            FiscalSettingsRepository::map,
            "find fiscal settings");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Places a store under a regime, or changes what it is under. Documents already issued keep the
   * stamps they were issued with; only documents issued from now on are affected.
   *
   * @param s the settings to write; {@code updatedAt} is taken by the database
   */
  public void upsert(FiscalStoreSettings s) {
    exec(
        "INSERT INTO fiscal_store_settings (tenant_id, store_id, regime, tax_registration_number,"
            + " certificate_number, series_validation_code, updated_by)"
            + " VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, store_id) DO UPDATE SET regime = EXCLUDED.regime,"
            + " tax_registration_number = EXCLUDED.tax_registration_number,"
            + " certificate_number = EXCLUDED.certificate_number,"
            + " series_validation_code = EXCLUDED.series_validation_code,"
            + " updated_by = EXCLUDED.updated_by, updated_at = now()",
        ps -> {
          ps.setObject(1, s.tenantId());
          ps.setObject(2, s.storeId());
          ps.setString(3, s.regime());
          ps.setString(4, s.taxRegistrationNumber());
          ps.setString(5, s.certificateNumber());
          ps.setString(6, s.seriesValidationCode());
          ps.setObject(7, s.updatedBy());
        },
        "upsert fiscal settings");
  }

  private static FiscalStoreSettings map(ResultSet rs) throws SQLException {
    OffsetDateTime updated = rs.getObject(7, OffsetDateTime.class);
    return new FiscalStoreSettings(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        updated == null ? null : updated.toInstant(),
        (UUID) rs.getObject(8));
  }
}
