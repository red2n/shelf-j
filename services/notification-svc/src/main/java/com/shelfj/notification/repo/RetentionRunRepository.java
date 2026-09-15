package com.shelfj.notification.repo;

import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.service.Retention;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The retention purge of the notification log (21.16), and this service's outbox, which exists for
 * its announcement. Every query filters tenant_id first.
 */
@ApplicationScoped
public class RetentionRunRepository extends BaseOutboxRepository {

  /**
   * Deletes the log rows older than the cutoff, except a held customer's, and announces the run in
   * the same transaction. Rows are counted first under lock, so the run's figures are its own.
   *
   * @param classHeld whether a hold stops the whole class; then nothing is deleted
   */
  public Retention.Counts purgeLog(
      UUID tenantId,
      Instant cutoff,
      boolean classHeld,
      Set<UUID> heldCustomers,
      Function<Retention.Counts, OutboxRow> runEvent) {
    return inTx(
        c -> {
          List<UUID> due = new ArrayList<>();
          int held = 0;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT n.id, n.subject_id FROM notification_log n WHERE n.tenant_id = ?"
                      + " AND n.created_at < ? ORDER BY n.id FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, cutoff.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                UUID subject = rs.getObject("subject_id", UUID.class);
                if (classHeld || (subject != null && heldCustomers.contains(subject))) {
                  held++;
                } else {
                  due.add(rs.getObject("id", UUID.class));
                }
              }
            }
          }
          int rows = 0;
          if (!due.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "DELETE FROM notification_log WHERE tenant_id = ? AND id = ANY (?)")) {
              ps.setObject(1, tenantId);
              ps.setArray(2, c.createArrayOf("uuid", due.toArray()));
              rows = ps.executeUpdate();
            }
          }
          Retention.Counts counts = new Retention.Counts(rows, held);
          insertOutbox(c, runEvent.apply(counts));
          return counts;
        },
        "purge notification log");
  }

  /** Every business with a message in the log: the tenants a retention sweep visits. */
  public List<UUID> tenantsWithLog() {
    return query(
        "SELECT DISTINCT n.tenant_id FROM notification_log n WHERE n.tenant_id IS NOT NULL"
            + " ORDER BY n.tenant_id",
        ps -> {},
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants with notifications");
  }
}
