package com.shelfj.iam.api;

import com.shelfj.iam.service.AuthService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * One-shot bootstrap endpoint. Creates the first PLATFORM_ADMIN when none exists. Refuses if a
 * PLATFORM_ADMIN already exists — so it is safe to leave enabled in non-production environments
 * without becoming a persistent privilege-escalation hole.
 *
 * <p>NOT exposed behind JWT auth deliberately: a brand-new deployment has no admin yet. The
 * built-in guard (fails if any PLATFORM_ADMIN exists) is the protection.
 */
@Path("/bootstrap")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Bootstrap")
public class BootstrapResource {

  @Inject AuthService svc;

  @Schema(name = "BootstrapRequest")
  public record BootstrapRequest(
      @Schema(description = "Email for the first platform administrator.") @NotBlank String email,
      @Schema(description = "Initial password for the account.") @NotBlank @Size(min = 8, max = 100)
          String password) {}

  @Schema(name = "BootstrapResponse")
  public record BootstrapResponse(String userId, String email, String role) {}

  @Operation(
      summary = "Create the first platform administrator",
      description =
          "One-shot bootstrap for a fresh deployment. Not gated by JWT — the deployment has no"
              + " admin yet — but refuses if a PLATFORM_ADMIN already exists.")
  @APIResponse(responseCode = "201", description = "Platform administrator created")
  @APIResponse(responseCode = "409", description = "A platform administrator already exists")
  @POST
  @Path("/admin")
  public Response createPlatformAdmin(BootstrapRequest req) {
    Validations.validate(req);
    UUID userId = svc.bootstrapAdmin(req.email(), req.password());
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(new BootstrapResponse(userId.toString(), req.email(), "PLATFORM_ADMIN")))
        .build();
  }
}
