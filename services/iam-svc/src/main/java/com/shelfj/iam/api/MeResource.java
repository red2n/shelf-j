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

/**
 * {@code GET /auth/me} — returns the current principal. iam-svc verifies the access token itself
 * (it owns JWT), reading the {@code Authorization: Bearer <token>} header directly rather than
 * relying on gateway-forwarded headers.
 */
@Path("/auth/me")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

  @Inject JwtService jwt;
  @Inject AuthService auth;

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
            user.email()));
  }
}
