package com.shelfj.customer.messaging;

import com.shelfj.customer.service.RetentionPurgeService;
import com.shelfj.service.Retention;
import com.shelfj.service.RetentionSweeperBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The daily purge of inactive customer records (21.16), tenant by tenant. */
@ApplicationScoped
public class RetentionSweeper extends RetentionSweeperBase {

  @Inject RetentionPurgeService service;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @Override
  protected String name() {
    return "customer-retention-sweeper";
  }

  @Override
  protected List<UUID> tenants() {
    return service.tenants();
  }

  @Override
  protected Optional<Retention.Run> purge(UUID tenantId) {
    return service.purge(tenantId);
  }
}
