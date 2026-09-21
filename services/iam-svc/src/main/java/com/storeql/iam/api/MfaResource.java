package com.storeql.iam.api;

import com.storeql.iam.domain.User;
import com.storeql.iam.dto.Dtos.TokenResponse;
import com.storeql.iam.dto.MfaDtos;
import com.storeql.iam.service.AuthService;
import com.storeql.iam.service.MfaService;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Second factors (20.12): answering one at sign-in, setting them up, taking them away, a business's
 * rule about who must have one, and the lost-phone reset.
 *
 * <p>Two routes are reached without a token, by whoever holds the {@code mfaToken} a sign-in
 * answered with. Every other route belongs to the signed-in login itself, staff or shopper — and is
 * all that an enrolment token (a login that owes a factor it has yet to set up) can reach.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Second factors")
public class MfaResource {

  @Inject MfaService mfa;
  @Inject AuthService auth;
  @Inject TenantContext ctx;

  // ── at sign-in ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Answer a sign-in's second factor",
      description =
          "Public. The mfaToken names a sign-in whose password was right; method is TOTP,"
              + " RECOVERY_CODE or PASSKEY. A handful of wrong answers ends the wait.")
  @APIResponse(responseCode = "200", description = "The token pair")
  @APIResponse(responseCode = "401", description = "Wrong, or the sign-in is no longer waiting")
  @POST
  @Path("/mfa/login")
  public ApiResponse<TokenResponse> login(MfaDtos.MfaLoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.completeMfaLogin(req));
  }

  @Operation(
      summary = "The challenge a passkey signs for a waiting sign-in",
      description = "Public. What navigator.credentials.get needs.")
  @APIResponse(responseCode = "200", description = "The request options")
  @POST
  @Path("/mfa/login/passkey-options")
  public ApiResponse<MfaDtos.PasskeyRequestOptions> passkeyOptions(MfaDtos.MfaTokenRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(mfa.passkeyOptions(req.mfaToken()));
  }

  // ── the login's own factors ─────────────────────────────────────────────────

  @Operation(summary = "Where this login stands with second factors")
  @GET
  @Path("/mfa")
  public ApiResponse<MfaDtos.MfaStatus> status() {
    return ApiResponse.ok(mfa.status(ctx.requireUserId(), ctx.tenantId(), ctx.roles()));
  }

  @Operation(
      summary = "Start setting up an authenticator app",
      description =
          "A new secret, pending until POST /auth/mfa/totp/confirm proves the app has it.")
  @POST
  @Path("/mfa/totp")
  public ApiResponse<MfaDtos.TotpEnrolment> beginTotp() {
    User me = auth.lookupUser(ctx.requireUserId());
    return ApiResponse.ok(mfa.beginTotp(me.id(), me.email()));
  }

  @Operation(
      summary = "Confirm the authenticator app with a code",
      description =
          "Answers the recovery codes, once, when this is the login's first factor — and the real"
              + " token pair when the caller held an enrolment token.")
  @POST
  @Path("/mfa/totp/confirm")
  public ApiResponse<MfaDtos.FactorEnrolled> confirmTotp(
      MfaDtos.CodeRequest req,
      @HeaderParam(HttpHeaders.AUTH_SCOPE) String scope,
      @HeaderParam(HttpHeaders.AUTH_METHODS) String methods) {
    Validations.validate(req);
    UUID me = ctx.requireUserId();
    List<String> codes = mfa.confirmTotp(me, req.code());
    return ApiResponse.ok(
        new MfaDtos.FactorEnrolled(codes, tokensIfEnrolling(scope, methods, me, "otp")));
  }

  @Operation(summary = "Remove the authenticator app", description = "Asks for the password.")
  @APIResponse(responseCode = "409", description = "A second factor is required of this login")
  @POST
  @Path("/mfa/totp/remove")
  public ApiResponse<String> removeTotp(MfaDtos.PasswordRequest req) {
    Validations.validate(req);
    mfa.removeTotp(auth.lookupUser(ctx.requireUserId()), ctx.roles(), req.password());
    return ApiResponse.ok("removed");
  }

  @Operation(
      summary = "New recovery codes",
      description = "The old ones stop working. Asks for the password.")
  @POST
  @Path("/mfa/recovery-codes")
  public ApiResponse<MfaDtos.FactorEnrolled> recoveryCodes(MfaDtos.PasswordRequest req) {
    Validations.validate(req);
    User me = auth.lookupUser(ctx.requireUserId());
    return ApiResponse.ok(
        new MfaDtos.FactorEnrolled(mfa.regenerateRecoveryCodes(me, req.password()), null));
  }

  @Operation(
      summary = "Start registering a passkey",
      description = "What navigator.credentials.create needs, and a token naming the registration.")
  @POST
  @Path("/mfa/passkeys/options")
  public ApiResponse<MfaDtos.PasskeyCreationOptions> beginPasskey() {
    User me = auth.lookupUser(ctx.requireUserId());
    return ApiResponse.ok(mfa.beginPasskey(me.id(), me.email()));
  }

  @Operation(summary = "Register the passkey the browser made")
  @POST
  @Path("/mfa/passkeys")
  public ApiResponse<MfaDtos.FactorEnrolled> finishPasskey(
      MfaDtos.PasskeyRegistration req,
      @HeaderParam(HttpHeaders.AUTH_SCOPE) String scope,
      @HeaderParam(HttpHeaders.AUTH_METHODS) String methods) {
    Validations.validate(req);
    UUID me = ctx.requireUserId();
    List<String> codes = mfa.finishPasskey(me, req);
    return ApiResponse.ok(
        new MfaDtos.FactorEnrolled(codes, tokensIfEnrolling(scope, methods, me, "hwk")));
  }

  @Operation(summary = "Remove a passkey", description = "Asks for the password.")
  @APIResponse(responseCode = "409", description = "A second factor is required of this login")
  @POST
  @Path("/mfa/passkeys/{id}/remove")
  public ApiResponse<String> removePasskey(
      @PathParam("id") String id, MfaDtos.PasswordRequest req) {
    Validations.validate(req);
    mfa.removePasskey(
        auth.lookupUser(ctx.requireUserId()),
        ctx.roles(),
        Parsing.uuid(id, "passkey id"),
        req.password());
    return ApiResponse.ok("removed");
  }

  /**
   * A login that owed a factor and has just set one up is now signed in for real, as what its
   * enrolment token says it proved first: a password, or the business's identity provider.
   */
  private TokenResponse tokensIfEnrolling(String scope, String methods, UUID userId, String amr) {
    if (!HttpHeaders.SCOPE_MFA_ENROL.equals(scope)) return null;
    String first =
        methods == null || methods.isBlank() ? AuthService.AMR_PASSWORD : methods.split(",")[0];
    return auth.issueAfterSecondFactor(userId, first.trim(), amr);
  }

  // ── the business's rule, and the lost phone ─────────────────────────────────

  @Operation(
      summary = "Which tiers of staff must have a second factor",
      description = "OWNER or MANAGER.")
  @GET
  @Path("/admin/mfa-policy")
  public ApiResponse<MfaDtos.MfaPolicy> policy() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(mfa.policy(ctx.requireTenantId()));
  }

  @Operation(
      summary = "Set which tiers of staff must have a second factor",
      description =
          "OWNER only. A login in a required tier without a factor is made to set one up at its"
              + " next sign-in, and a session that was only a password's is not renewed.")
  @PUT
  @Path("/admin/mfa-policy")
  public ApiResponse<MfaDtos.MfaPolicy> putPolicy(MfaDtos.MfaPolicy req) {
    ctx.requireAnyRole("OWNER");
    Validations.validate(req);
    return ApiResponse.ok(
        mfa.putPolicy(ctx.requireTenantId(), ctx.requireUserId(), req.requiredTiers()));
  }

  @Operation(
      summary = "Reset a member of staff's second factors (a lost phone)",
      description =
          "OWNER for their own staff, PLATFORM_ADMIN for anybody; never one's own. Ends the"
              + " person's sessions.")
  @APIResponse(responseCode = "404", description = "Not a login of this business")
  @DELETE
  @Path("/admin/staff-users/{userId}/mfa")
  public ApiResponse<String> reset(@PathParam("userId") String userId) {
    ctx.requireAnyRole("OWNER", "PLATFORM_ADMIN");
    mfa.resetFor(ctx.requireUserId(), ctx.tenantId(), ctx.roles(), Parsing.uuid(userId, "userId"));
    return ApiResponse.ok("reset");
  }
}
