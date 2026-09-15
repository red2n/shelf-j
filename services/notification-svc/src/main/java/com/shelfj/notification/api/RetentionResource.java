package com.shelfj.notification.api;

import com.shelfj.notification.service.RetentionPurgeService;
import com.shelfj.service.Retention;
import com.shelfj.service.RetentionSweepResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code POST /admin/notifications/retention/sweep}: purge this business's notification log now, as
 * its schedule says (21.16). Under {@code /admin/}, so management only.
 */
@Path("/admin/notifications/retention")
@ApplicationScoped
@Tag(name = "Retention")
public class RetentionResource extends RetentionSweepResource {

  @Inject RetentionPurgeService service;

  @Override
  protected Optional<Retention.Run> purge(UUID tenantId) {
    return service.purge(tenantId);
  }
}
