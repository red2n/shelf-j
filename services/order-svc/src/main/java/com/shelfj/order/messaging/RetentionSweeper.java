package com.shelfj.order.messaging;

import com.shelfj.order.service.RetentionPurgeService;
import com.shelfj.service.Retention;
import com.shelfj.service.RetentionSweeperBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The daily purge of personal details on settled orders (21.16), tenant by tenant. */
@ApplicationScoped
public class RetentionSweeper extends RetentionSweeperBase {

  @Inject RetentionPurgeService service;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @Override
  protected String name() {
    return "order-retention-sweeper";
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
