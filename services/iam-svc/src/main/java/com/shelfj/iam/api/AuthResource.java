package com.shelfj.iam.api;

import com.shelfj.iam.dto.Dtos.ChangePasswordRequest;
import com.shelfj.iam.dto.Dtos.LoginRequest;
import com.shelfj.iam.dto.Dtos.LogoutRequest;
import com.shelfj.iam.dto.Dtos.RefreshRequest;
import com.shelfj.iam.dto.Dtos.RegisterRequest;
import com.shelfj.iam.dto.Dtos.TokenResponse;
import com.shelfj.iam.service.AuthService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Public authentication endpoints (README §9.1). These are reachable without a tenant/JWT — they
 * MINT identity. Thin controllers: validate DTO, delegate to {@link AuthService}, return the
 * envelope.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

  @Inject AuthService auth;

  @POST
  @Path("/register")
  public Response register(RegisterRequest req) {
    Validations.validate(req);
    TokenResponse tokens = auth.register(req.email(), req.password(), req.phone());
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(tokens)).build();
  }

  @POST
  @Path("/login")
  public ApiResponse<TokenResponse> login(LoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.login(req.email(), req.password()));
  }

  @POST
  @Path("/refresh")
  public ApiResponse<TokenResponse> refresh(RefreshRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.refresh(req.refreshToken()));
  }

  @POST
  @Path("/logout")
  public ApiResponse<String> logout(LogoutRequest req) {
    Validations.validate(req);
    auth.logout(req.refreshToken());
    return ApiResponse.ok("logged_out");
  }

  @PUT
  @Path("/change-password")
  public ApiResponse<String> changePassword(
      @HeaderParam("X-User-Id") String userIdHeader, ChangePasswordRequest req) {
    if (userIdHeader == null || userIdHeader.isBlank()) {
      throw ApiException.unauthorized("MISSING_USER_ID", "X-User-Id header required");
    }
    UUID userId;
    try {
      userId = UUID.fromString(userIdHeader);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          401, "INVALID_USER_ID", "X-User-Id must be a UUID", java.util.List.of(), e);
    }
    Validations.validate(req);
    auth.changePassword(userId, req.currentPassword(), req.newPassword());
    return ApiResponse.ok("password_changed");
  }
}
