package com.shelfj.iam.api;

import com.shelfj.iam.dto.Dtos.ChangePasswordRequest;
import com.shelfj.iam.dto.Dtos.LoginRequest;
import com.shelfj.iam.dto.Dtos.LogoutRequest;
import com.shelfj.iam.dto.Dtos.ProvisionStaffRequest;
import com.shelfj.iam.dto.Dtos.ProvisionStaffResponse;
import com.shelfj.iam.dto.Dtos.RefreshRequest;
import com.shelfj.iam.dto.Dtos.RegisterRequest;
import com.shelfj.iam.dto.Dtos.TokenResponse;
import com.shelfj.iam.service.AuthService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Public authentication endpoints (docs/ARCHITECTURE.md §10, iam-svc). These are reachable without a tenant/JWT — they
 * MINT identity. Thin controllers: validate DTO, delegate to {@link AuthService}, return the
 * envelope.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

  @Inject AuthService auth;
  @Inject TenantContext ctx;

  /**
   * Admin endpoint. Find-or-create a staff account by email and return the userId the caller
   * assigns a store role to via tenant-svc. Tenant comes from the JWT, never the body.
   *
   * <p>Also gated by AdminAuthorizationFilter on the {@code /admin/} path prefix, but asserted here
   * too rather than relying on that alone — a future rename/move of this path off {@code /admin/}
   * must not silently drop the management-role requirement.
   */
  @POST
  @Path("/admin/staff-users")
  public ApiResponse<ProvisionStaffResponse> provisionStaff(ProvisionStaffRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return ApiResponse.ok(auth.provisionStaff(ctx.requireTenantId(), req.email(), req.password()));
  }

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

  /**
   * Platform console login — distinct from {@link #login} so a PLATFORM_ADMIN credential is never
   * valid on a store/POS login screen, and a tenant staff credential is never valid here.
   */
  @POST
  @Path("/platform-login")
  public ApiResponse<TokenResponse> platformLogin(LoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.platformLogin(req.email(), req.password()));
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
  public ApiResponse<String> changePassword(ChangePasswordRequest req) {
    Validations.validate(req);
    auth.changePassword(ctx.requireUserId(), req.currentPassword(), req.newPassword());
    return ApiResponse.ok("password_changed");
  }
}
