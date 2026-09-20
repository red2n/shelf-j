package com.storeql.customer.api;

import com.storeql.customer.service.RetentionPurgeService;
import com.storeql.service.Retention;
import com.storeql.service.RetentionSweepResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code POST /admin/customers/retention/sweep}: erase this business's inactive customer records
 * now, as its schedule says (21.16). Under {@code /admin/}, so management only.
 */
@Path("/admin/customers/retention")
@ApplicationScoped
@Tag(name = "Retention")
public class RetentionResource extends RetentionSweepResource {

  @Inject RetentionPurgeService service;

  @Override
  protected Optional<Retention.Run> purge(UUID tenantId) {
    return service.purge(tenantId);
  }
}
