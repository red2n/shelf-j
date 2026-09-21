package com.storeql.customer.service;

import com.storeql.customer.repo.CustomerRepository;
import com.storeql.service.Retention;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * This service's retention purge (21.16): customer records nothing has happened on for the period
 * the business set for {@code CUSTOMER_RECORDS}, each erased as the customer could have asked
 * &mdash; the same erasure, with the same event, so every copy in every service goes with it.
 */
@ApplicationScoped
public class RetentionPurgeService {

  private static final Logger LOG = System.getLogger(RetentionPurgeService.class.getName());
  static final String SERVICE = "customer-svc";
  static final String TOPIC = "storeql.customer.retention-run-completed";

  @Inject CustomerRepository repo;
  @Inject CustomerService customers;
  @Inject Retention retention;

  /**
   * Purges one tenant as its schedule says. Each erasure commits on its own, as an erasure does;
   * the run's announcement follows with what was done.
   *
   * @return the run, or empty when the business has set no period
   */
  public Optional<Retention.Run> purge(UUID tenantId) {
    return retention.purge(
        tenantId,
        SERVICE,
        Retention.CUSTOMER_RECORDS,
        (cutoff, classHeld, sheet, payload) -> {
          Set<UUID> heldCustomers = sheet.heldSubjects(Retention.CUSTOMER_RECORDS, "CUSTOMER");
          int rows = 0;
          int held = 0;
          for (UUID customerId : repo.inactiveSince(tenantId, cutoff)) {
            if (classHeld || heldCustomers.contains(customerId)) {
              held++;
              continue;
            }
            try {
              customers.anonymize(tenantId, customerId);
              rows++;
            } catch (ApiException e) {
              // Erased by someone else since the list was read: nothing to do for this one.
              LOG.log(Level.DEBUG, "Customer {0} not purged: {1}", customerId, e.getMessage());
            }
          }
          Retention.Counts counts = new Retention.Counts(rows, held);
          repo.recordRun(Retention.announce(TOPIC, tenantId, payload).apply(counts));
          return counts;
        });
  }

  public List<UUID> tenants() {
    return repo.tenantsWithCustomers();
  }
}
