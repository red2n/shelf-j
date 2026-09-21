package com.storeql.notification.service;

import com.storeql.notification.repo.RetentionRunRepository;
import com.storeql.service.Retention;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * This service's retention purge (21.16): the log of messages sent, older than the period the
 * business set for {@code NOTIFICATION_LOG}, except a held customer's.
 */
@ApplicationScoped
public class RetentionPurgeService {

  static final String SERVICE = "notification-svc";
  static final String TOPIC = "storeql.notification.retention-run-completed";

  @Inject RetentionRunRepository repo;
  @Inject Retention retention;

  /**
   * Purges one tenant as its schedule says.
   *
   * @return the run, or empty when the business has set no period
   */
  public Optional<Retention.Run> purge(UUID tenantId) {
    return retention.purge(
        tenantId,
        SERVICE,
        Retention.NOTIFICATION_LOG,
        (cutoff, classHeld, sheet, payload) ->
            repo.purgeLog(
                tenantId,
                cutoff,
                classHeld,
                sheet.heldSubjects(Retention.NOTIFICATION_LOG, "CUSTOMER"),
                Retention.announce(TOPIC, tenantId, payload)));
  }

  public List<UUID> tenants() {
    return repo.tenantsWithLog();
  }
}
