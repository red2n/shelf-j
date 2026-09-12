package com.shelfj.iam.api;

import com.shelfj.iam.domain.User;
import com.shelfj.iam.dto.Dtos.MeResponse;
import com.shelfj.iam.service.AuthService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code GET /auth/me} — returns the current principal, as identified by the gateway.
 *
 * <p>The gateway verifies the access token and forwards only the identity it carries, never the
 * {@code Authorization} header, so the caller is read from {@link TenantContext} like every other
 * endpoint (golden rule #2). Reading the bearer token here instead made this endpoint answer 401 to
 * every caller that came through the gateway.
 */
@Path("/auth/me")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Me")
public class MeResource {

  @Inject TenantContext ctx;
  @Inject AuthService auth;

  @Operation(
      summary = "Get the current principal",
      description = "The authenticated user's identity and the roles their access token carries.")
  @APIResponse(responseCode = "200", description = "Current principal")
  @APIResponse(responseCode = "401", description = "No authenticated user")
  @GET
  public ApiResponse<MeResponse> me() {
    User user = auth.lookupUser(ctx.requireUserId());
    return ApiResponse.ok(
        new MeResponse(
            user.id().toString(),
            user.tenantId() == null ? null : user.tenantId().toString(),
            user.type(),
            List.copyOf(ctx.roles()),
            user.email(),
            user.phone(),
            user.status(),
            user.createdAt() == null ? null : user.createdAt().toString()));
  }
}
