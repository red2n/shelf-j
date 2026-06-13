package com.shelfj.iam.api;

import com.shelfj.iam.auth.Passwords;
import com.shelfj.iam.domain.User;
import com.shelfj.iam.repo.UserRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
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
import java.time.Instant;
import java.util.UUID;

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
public class BootstrapResource {

  @Inject UserRepository users;
  @Inject Passwords passwords;

  public record BootstrapRequest(
      @NotBlank String email, @NotBlank @Size(min = 8, max = 100) String password) {}

  public record BootstrapResponse(String userId, String email, String role) {}

  @POST
  @Path("/admin")
  public Response createPlatformAdmin(BootstrapRequest req) {
    if (users.platformAdminExists()) {
      throw new ApiException(
          409,
          "BOOTSTRAP_ALREADY_DONE",
          "A PLATFORM_ADMIN already exists. Use the login endpoint.",
          java.util.List.of(),
          null);
    }

    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    var user =
        new User(
            userId,
            null,
            User.TYPE_STAFF,
            req.email(),
            null,
            passwords.hash(req.password()),
            User.STATUS_ACTIVE,
            now,
            now);

    users.createPlatformAdmin(user);
    users.audit(null, userId, "PLATFORM_ADMIN_BOOTSTRAPPED", req.email());

    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(new BootstrapResponse(userId.toString(), req.email(), "PLATFORM_ADMIN")))
        .build();
  }
}
