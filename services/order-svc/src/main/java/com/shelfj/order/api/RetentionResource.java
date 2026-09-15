package com.shelfj.order.api;

import com.shelfj.order.service.RetentionPurgeService;
import com.shelfj.service.Retention;
import com.shelfj.service.RetentionSweepResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code POST /admin/orders/retention/sweep}: purge the personal details on this business's settled
 * orders now, as its schedule says (21.16). Under {@code /admin/}, so management only.
 */
@Path("/admin/orders/retention")
@ApplicationScoped
@Tag(name = "Retention")
public class RetentionResource extends RetentionSweepResource {

  @Inject RetentionPurgeService service;

  @Override
  protected Optional<Retention.Run> purge(UUID tenantId) {
    return service.purge(tenantId);
  }
}
