package com.shelfj.service;

import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST .../retention/sweep}: runs this service's purge for the caller's business now, as its
 * schedule says, and returns the run (21.16). A subclass gives it a path under {@code /admin/},
 * which the authorisation filter gates to management, and the purge to run.
 */
@Produces(MediaType.APPLICATION_JSON)
public abstract class RetentionSweepResource {

  @Inject protected TenantContext ctx;

  /** One tenant's purge, as the schedule says; empty when it has set no period. */
  protected abstract Optional<Retention.Run> purge(UUID tenantId);

  /**
   * Runs the purge now.
   *
   * @return the run: what was purged and what a hold kept
   * @throws com.shelfj.web.ApiException {@code 409 RETENTION_PERIOD_NOT_SET} when the business has
   *     set no period for this class; {@code 503} when the schedule cannot be read
   */
  @POST
  @Path("/sweep")
  public ApiResponse<Retention.Run> sweep() {
    Retention.Run run =
        purge(ctx.requireTenantId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "RETENTION_PERIOD_NOT_SET",
                        "The business has set no retention period for this class; set one under"
                            + " Legal → Data retention first"));
    return ApiResponse.ok(run, ApiResponse.Meta.of(ctx.requestId()));
  }
}
