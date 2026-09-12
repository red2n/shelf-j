package com.shelfj.order.repo;

import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The security module registered for a German store (18.5). The SIMULATED provider's counters live
 * here and move under a row lock, because a signature counter that can repeat is not a signature
 * counter.
 */
@ApplicationScoped
public class TseDeviceRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, store_id, provider, client_id, serial_number, public_key,"
          + " signature_algorithm, time_format, private_key, external_tss_id, signature_counter,"
          + " transaction_counter, registered_at, registered_by";

  /**
   * The device a store signs with.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @return the device, or empty when none is registered
   */
  public Optional<TseDevice> findByStore(UUID tenantId, UUID storeId) {
    List<TseDevice> rows =
        query(
            "SELECT " + COLUMNS + " FROM tse_devices WHERE tenant_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            TseDeviceRepository::map,
            "find tse device");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Registers a device for a store. Refused by the unique key when one exists: a store has one
   * recording system and the law counts one module for it.
   *
   * @param d the device, with its id already minted
   */
  public void insert(TseDevice d) {
    exec(
        "INSERT INTO tse_devices (id, tenant_id, store_id, provider, client_id, serial_number,"
            + " public_key, signature_algorithm, time_format, private_key, external_tss_id,"
            + " signature_counter, transaction_counter, registered_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0,?)",
        ps -> {
          ps.setObject(1, d.id());
          ps.setObject(2, d.tenantId());
          ps.setObject(3, d.storeId());
          ps.setString(4, d.provider());
          ps.setString(5, d.clientId());
          ps.setString(6, d.serialNumber());
          ps.setString(7, d.publicKey());
          ps.setString(8, d.signatureAlgorithm());
          ps.setString(9, d.timeFormat());
          ps.setString(10, d.privateKey());
          ps.setString(11, d.externalTssId());
          ps.setObject(12, d.registeredBy());
        },
        "register tse device");
  }

  /**
   * Removes a store's device so another can be registered — the only mutation the register allows,
   * and only the settings path calls it, when a manager replaces a device.
   *
   * @return whether a row was removed
   */
  public boolean deleteByStore(UUID tenantId, UUID storeId) {
    return inTx(
        c -> {
          try (var st =
              c.prepareStatement("DELETE FROM tse_devices WHERE tenant_id = ? AND store_id = ?")) {
            st.setObject(1, tenantId);
            st.setObject(2, storeId);
            return st.executeUpdate() > 0;
          }
        },
        "delete tse device");
  }

  /** The two counters a simulated device advanced for one transaction. */
  public record Counters(long transactionNumber, long signatureCounter) {}

  /**
   * Advances the SIMULATED device's counters for one transaction, atomically: {@code UPDATE …
   * RETURNING} under the row lock, so two tills signing at once cannot share a number.
   *
   * @param deviceId the device
   * @return the transaction number and signature counter this transaction uses
   */
  public Counters nextCounters(UUID deviceId) {
    return inTx(
        c -> {
          try (var st =
              c.prepareStatement(
                  "UPDATE tse_devices SET transaction_counter = transaction_counter + 1,"
                      + " signature_counter = signature_counter + 1"
                      + " WHERE id = ? RETURNING transaction_counter, signature_counter")) {
            st.setObject(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
              if (!rs.next()) {
                throw new SQLException("tse device " + deviceId + " vanished");
              }
              return new Counters(rs.getLong(1), rs.getLong(2));
            }
          }
        },
        "advance tse counters");
  }

  private static TseDevice map(ResultSet rs) throws SQLException {
    OffsetDateTime registered = rs.getObject(14, OffsetDateTime.class);
    return new TseDevice(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        (UUID) rs.getObject(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        rs.getString(9),
        rs.getString(10),
        rs.getString(11),
        rs.getLong(12),
        rs.getLong(13),
        registered == null ? null : registered.toInstant(),
        (UUID) rs.getObject(15));
  }
}
