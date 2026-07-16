package com.shelfj.iam.api;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.iam.auth.JwtService;
import com.shelfj.iam.domain.User;
import com.shelfj.iam.dto.Dtos.MeResponse;
import com.shelfj.iam.service.AuthService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code GET /auth/me} — returns the current principal. iam-svc verifies the access token itself
 * (it owns JWT), reading the {@code Authorization: Bearer <token>} header directly rather than
 * relying on gateway-forwarded headers.
 */
@Path("/auth/me")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Me")
public class MeResource {

  @Inject JwtService jwt;
  @Inject AuthService auth;

  @Operation(
      summary = "Get the current principal",
      description =
          "Verifies the bearer access token itself (iam-svc owns JWT verification) and returns the"
              + " authenticated user's identity and roles.")
  @APIResponse(responseCode = "200", description = "Current principal")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @GET
  public ApiResponse<MeResponse> me(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw ApiException.unauthorized("MISSING_BEARER", "Authorization: Bearer <token> required");
    }
    String token = authorization.substring("Bearer ".length()).trim();
    DecodedJWT decoded;
    try {
      decoded = jwt.verify(token);
    } catch (JWTVerificationException e) {
      throw new ApiException(401, "INVALID_TOKEN", "Access token invalid or expired", List.of(), e);
    }

    UUID userId = UUID.fromString(decoded.getSubject());
    User user = auth.lookupUser(userId);
    List<String> roles = decoded.getClaim("roles").asList(String.class);

    return ApiResponse.ok(
        new MeResponse(
            user.id().toString(),
            user.tenantId() == null ? null : user.tenantId().toString(),
            user.type(),
            roles == null ? List.of() : roles,
            user.email(),
            user.phone(),
            user.status(),
            user.createdAt() == null ? null : user.createdAt().toString()));
  }
}
