package com.shelfj.pricing.repo;

import com.shelfj.pricing.domain.Domain.VatRegistration;
import com.shelfj.pricing.domain.Domain.VatReturn;
import com.shelfj.pricing.domain.Domain.VatReturnSubmission;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The VAT registration and the append-only record of every return filed (18.5). */
@ApplicationScoped
public class MtdRepository extends BaseJdbcRepository {

  private static final String REG_COLUMNS =
      "tenant_id, vrn, provider, hmrc_access_token, hmrc_refresh_token, hmrc_token_expires_at,"
          + " connected_at, updated_at, updated_by";

  private static final String SUB_COLUMNS =
      "id, tenant_id, vrn, period_key, period_from, period_to, box1, box2, box3, box4, box5,"
          + " box6, box7, box8, box9, finalised, provider, status, submitted_at, submitted_by,"
          + " processing_date, form_bundle_number, payment_indicator, charge_ref_number,"
          + " receipt_id, receipt_timestamp, error_code, error_message";

  /**
   * @param tenantId owning tenant
   * @return the registration, or empty when the tenant has not registered a VAT number
   */
  public Optional<VatRegistration> findRegistration(UUID tenantId) {
    List<VatRegistration> rows =
        query(
            "SELECT " + REG_COLUMNS + " FROM vat_registrations WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            MtdRepository::mapRegistration,
            "find vat registration");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Registers, or changes the number or the provider; tokens are written as given. */
  public void upsertRegistration(VatRegistration r) {
    exec(
        "INSERT INTO vat_registrations (tenant_id, vrn, provider, hmrc_access_token,"
            + " hmrc_refresh_token, hmrc_token_expires_at, connected_at, updated_by)"
            + " VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id) DO UPDATE SET vrn = EXCLUDED.vrn,"
            + " provider = EXCLUDED.provider, hmrc_access_token = EXCLUDED.hmrc_access_token,"
            + " hmrc_refresh_token = EXCLUDED.hmrc_refresh_token,"
            + " hmrc_token_expires_at = EXCLUDED.hmrc_token_expires_at,"
            + " connected_at = EXCLUDED.connected_at, updated_by = EXCLUDED.updated_by,"
            + " updated_at = now()",
        ps -> {
          ps.setObject(1, r.tenantId());
          ps.setString(2, r.vrn());
          ps.setString(3, r.provider());
          ps.setString(4, r.accessTokenCipher());
          ps.setString(5, r.refreshTokenCipher());
          ps.setObject(6, odt(r.tokenExpiresAt()));
          ps.setObject(7, odt(r.connectedAt()));
          ps.setObject(8, r.updatedBy());
        },
        "upsert vat registration");
  }

  /** Records a return as filed and answered. Append-only: nothing updates or deletes a row. */
  public void insertSubmission(VatReturnSubmission s) {
    exec(
        "INSERT INTO vat_return_submissions ("
            + SUB_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          VatReturn b = s.boxes();
          int i = 1;
          ps.setObject(i++, s.id());
          ps.setObject(i++, s.tenantId());
          ps.setString(i++, s.vrn());
          ps.setString(i++, s.periodKey());
          ps.setObject(i++, odt(s.periodFrom()));
          ps.setObject(i++, odt(s.periodTo()));
          ps.setBigDecimal(i++, b.box1());
          ps.setBigDecimal(i++, b.box2());
          ps.setBigDecimal(i++, b.box3());
          ps.setBigDecimal(i++, b.box4());
          ps.setBigDecimal(i++, b.box5());
          ps.setBigDecimal(i++, b.box6());
          ps.setBigDecimal(i++, b.box7());
          ps.setBigDecimal(i++, b.box8());
          ps.setBigDecimal(i++, b.box9());
          ps.setBoolean(i++, s.finalised());
          ps.setString(i++, s.provider());
          ps.setString(i++, s.status());
          ps.setObject(i++, odt(s.submittedAt()));
          ps.setObject(i++, s.submittedBy());
          ps.setObject(i++, odt(s.processingDate()));
          ps.setString(i++, s.formBundleNumber());
          ps.setString(i++, s.paymentIndicator());
          ps.setString(i++, s.chargeRefNumber());
          ps.setString(i++, s.receiptId());
          ps.setObject(i++, odt(s.receiptTimestamp()));
          ps.setString(i++, s.errorCode());
          ps.setString(i, s.errorMessage());
        },
        "record vat submission");
  }

  /**
   * @param tenantId owning tenant; the first condition
   * @param limit maximum rows, newest first
   * @return every return filed, accepted or refused
   */
  public List<VatReturnSubmission> listSubmissions(UUID tenantId, int limit) {
    return query(
        "SELECT "
            + SUB_COLUMNS
            + " FROM vat_return_submissions WHERE tenant_id = ?"
            + " ORDER BY submitted_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        MtdRepository::mapSubmission,
        "list vat submissions");
  }

  /**
   * @return one filing, or empty when it is not this tenant's
   */
  public Optional<VatReturnSubmission> findSubmission(UUID tenantId, UUID id) {
    List<VatReturnSubmission> rows =
        query(
            "SELECT " + SUB_COLUMNS + " FROM vat_return_submissions WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            MtdRepository::mapSubmission,
            "find vat submission");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** The periods already filed and accepted under a number — what an obligation is fulfilled by. */
  public Set<String> acceptedPeriodKeys(UUID tenantId, String vrn) {
    return new HashSet<>(
        query(
            "SELECT period_key FROM vat_return_submissions"
                + " WHERE tenant_id = ? AND vrn = ? AND status = 'ACCEPTED'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, vrn);
            },
            rs -> rs.getString(1),
            "accepted period keys"));
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, int col) throws SQLException {
    OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static VatRegistration mapRegistration(ResultSet rs) throws SQLException {
    return new VatRegistration(
        (UUID) rs.getObject(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        instant(rs, 6),
        instant(rs, 7),
        instant(rs, 8),
        (UUID) rs.getObject(9));
  }

  private static VatReturnSubmission mapSubmission(ResultSet rs) throws SQLException {
    Instant from = instant(rs, 5);
    Instant to = instant(rs, 6);
    VatReturn boxes =
        new VatReturn(
            rs.getBigDecimal(7),
            rs.getBigDecimal(8),
            rs.getBigDecimal(9),
            rs.getBigDecimal(10),
            rs.getBigDecimal(11),
            rs.getBigDecimal(12),
            rs.getBigDecimal(13),
            rs.getBigDecimal(14),
            rs.getBigDecimal(15),
            from.toString(),
            to.toString());
    return new VatReturnSubmission(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        rs.getString(3),
        rs.getString(4),
        from,
        to,
        boxes,
        rs.getBoolean(16),
        rs.getString(17),
        rs.getString(18),
        instant(rs, 19),
        (UUID) rs.getObject(20),
        instant(rs, 21),
        rs.getString(22),
        rs.getString(23),
        rs.getString(24),
        rs.getString(25),
        instant(rs, 26),
        rs.getString(27),
        rs.getString(28));
  }
}
