package com.storeql.order.service;

import com.storeql.order.repo.OrderRepository;
import com.storeql.service.Retention;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * This service's retention purge (21.16): the personal details on settled orders — the delivery
 * name, address and phone, the notes, the address a receipt went to, the number a recall notice
 * held — older than the period the business set for {@code ORDER_PERSONAL_DATA}. The order and its
 * lines stay for the transaction's own period; the person leaves.
 */
@ApplicationScoped
public class RetentionPurgeService {

  static final String SERVICE = "order-svc";
  static final String TOPIC = "storeql.order.retention-run-completed";

  @Inject OrderRepository repo;
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
        Retention.ORDER_PERSONAL_DATA,
        (cutoff, classHeld, sheet, payload) ->
            repo.purgePersonalData(
                tenantId,
                cutoff,
                classHeld,
                sheet.heldSubjects(Retention.ORDER_PERSONAL_DATA, "ORDER"),
                sheet.heldSubjects(Retention.ORDER_PERSONAL_DATA, "CUSTOMER"),
                Retention.announce(TOPIC, tenantId, payload)));
  }

  public List<UUID> tenants() {
    return repo.tenantsWithOrders();
  }
}
