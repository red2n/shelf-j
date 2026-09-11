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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public authentication endpoints (docs/ARCHITECTURE.md §10, iam-svc). These are reachable without
 * a tenant/JWT — they MINT identity. Thin controllers: validate DTO, delegate to {@link
 * AuthService}, return the envelope.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Auth")
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
  @Operation(
      summary = "Provision a staff account",
      description =
          "Find-or-create a staff account by email. Returns the userId the caller assigns a store"
              + " role to via tenant-svc. Requires PLATFORM_ADMIN, OWNER, or MANAGER.")
  @APIResponse(responseCode = "200", description = "Staff user found or created")
  @APIResponse(responseCode = "403", description = "Caller lacks an admin/owner/manager role")
  @POST
  @Path("/admin/staff-users")
  public ApiResponse<ProvisionStaffResponse> provisionStaff(ProvisionStaffRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return ApiResponse.ok(auth.provisionStaff(ctx.requireTenantId(), req.email(), req.password()));
  }

  @Operation(
      summary = "Register a new customer",
      description = "Public self-signup. No JWT required — this endpoint mints identity.")
  @APIResponse(responseCode = "201", description = "Account created, tokens issued")
  @APIResponse(responseCode = "409", description = "Email already bound to a different tenant")
  @POST
  @Path("/register")
  public Response register(RegisterRequest req) {
    Validations.validate(req);
    TokenResponse tokens = auth.register(req.email(), req.password(), req.phone());
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(tokens)).build();
  }

  @Operation(
      summary = "Log in with email and password",
      description = "Public tenant/customer login. No JWT required.")
  @APIResponse(responseCode = "200", description = "Credentials valid, tokens issued")
  @APIResponse(responseCode = "401", description = "Invalid email or password")
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
  @Operation(
      summary = "Log in to the platform console",
      description =
          "PLATFORM_ADMIN-only login, kept separate from /auth/login so tenant staff and platform"
              + " admin credentials are never interchangeable.")
  @APIResponse(responseCode = "200", description = "Credentials valid, tokens issued")
  @APIResponse(responseCode = "401", description = "Invalid email or password")
  @APIResponse(responseCode = "403", description = "Credential is valid but not a PLATFORM_ADMIN")
  @POST
  @Path("/platform-login")
  public ApiResponse<TokenResponse> platformLogin(LoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.platformLogin(req.email(), req.password()));
  }

  @Operation(
      summary = "Exchange a refresh token for a new access token",
      description = "Public — authenticates via the refresh token itself, not a bearer JWT.")
  @APIResponse(responseCode = "200", description = "New token pair issued")
  @APIResponse(responseCode = "401", description = "Refresh token invalid, expired, or revoked")
  @POST
  @Path("/refresh")
  public ApiResponse<TokenResponse> refresh(RefreshRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.refresh(req.refreshToken()));
  }

  @Operation(
      summary = "Log out",
      description = "Revokes the given refresh token. Idempotent-friendly: revoking twice is safe.")
  @APIResponse(responseCode = "200", description = "Refresh token revoked")
  @POST
  @Path("/logout")
  public ApiResponse<String> logout(LogoutRequest req) {
    Validations.validate(req);
    auth.logout(req.refreshToken());
    return ApiResponse.ok("logged_out");
  }

  @Operation(
      summary = "Change the current user's password",
      description =
          "Requires the current password to re-verify identity before setting the new one.")
  @APIResponse(responseCode = "200", description = "Password changed")
  @APIResponse(responseCode = "401", description = "Current password is incorrect")
  @PUT
  @Path("/change-password")
  public ApiResponse<String> changePassword(ChangePasswordRequest req) {
    Validations.validate(req);
    auth.changePassword(ctx.requireUserId(), req.currentPassword(), req.newPassword());
    return ApiResponse.ok("password_changed");
  }

  @Operation(
      summary = "Delete my account",
      description =
          "The account holder deletes their own login (SJ-D43). Requires the password again, so a"
              + " session left open on a shared device cannot do it. The login's email, phone and"
              + " password are erased, every refresh token is revoked, and AccountDeleted tells"
              + " other services. Already-issued access tokens stay valid until they expire, as"
              + " they do after logout. Records a shop holds — orders, loyalty, its own customer"
              + " profile — stay with that shop, which erases them itself on request. A staff"
              + " account is removed by the business that employs its holder, not here.")
  @APIResponse(responseCode = "200", description = "Account deleted")
  @APIResponse(responseCode = "401", description = "Password is incorrect")
  @APIResponse(responseCode = "403", description = "A staff account cannot be deleted here")
  @POST
  @Path("/delete-account")
  public ApiResponse<String> deleteAccount(com.shelfj.iam.dto.Dtos.DeleteAccountRequest req) {
    Validations.validate(req);
    auth.deleteAccount(ctx.requireUserId(), req.password());
    return ApiResponse.ok("account_deleted");
  }
}
