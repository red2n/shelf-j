package com.storeql.iam.api;

import com.storeql.iam.dto.Dtos.TokenResponse;
import com.storeql.iam.dto.SsoDtos;
import com.storeql.iam.service.SsoService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Single sign-on through a business's own identity provider (20.x, SSO over OpenID Connect).
 *
 * <p>Three routes are reached without a token, because they are how a person without one signs in:
 * starting a sign-in, the provider sending the browser back, and the app trading the ticket it came
 * back with. The rest are the business's own settings: an owner changes them, an owner or a manager
 * reads them.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Single sign-on")
public class SsoResource {

  @Inject SsoService sso;
  @Inject TenantContext ctx;

  // ── signing in ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Start a sign-in through a business's identity provider",
      description =
          "Public. Names the business by its sign-in name, and carries the S256 challenge of a"
              + " verifier the app keeps. Answers where to send the browser.")
  @APIResponse(responseCode = "200", description = "The provider's authorization URL")
  @APIResponse(responseCode = "404", description = "No business signs in with that name")
  @APIResponse(responseCode = "502", description = "The business's provider could not be used")
  @POST
  @Path("/sso/start")
  public ApiResponse<SsoDtos.Started> start(SsoDtos.StartRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(sso.start(req));
  }

  @Operation(
      summary = "Where the identity provider sends the browser back",
      description =
          "Public: the redirect URI a business registers with its provider. Always answers 303 to"
              + " the app — #sso_ticket=… when the person was matched to a login, #sso_error=CODE"
              + " when not. Nothing the provider said is passed on.")
  @APIResponse(responseCode = "303", description = "Back to the app")
  @GET
  @Path("/sso/callback")
  @Produces(MediaType.TEXT_PLAIN)
  public Response callback(
      @QueryParam("code") String code,
      @QueryParam("state") String state,
      @QueryParam("error") String error) {
    return Response.seeOther(URI.create(sso.returned(code, state, error)))
        .header("Cache-Control", "no-store")
        .build();
  }

  @Operation(
      summary = "Trade the ticket a sign-in came back with",
      description =
          "Public. The ticket and the verifier the start was made with. Answers what a password"
              + " sign-in answers: the token pair, a second factor owed (mfaRequired) or one to"
              + " set up (mfaEnrolmentRequired). A ticket works once, and only with its verifier.")
  @APIResponse(responseCode = "200", description = "The sign-in")
  @APIResponse(responseCode = "401", description = "Spent, expired, or not this app's")
  @POST
  @Path("/sso/token")
  public ApiResponse<TokenResponse> token(SsoDtos.TicketRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(sso.redeem(req));
  }

  // ── the business's provider ─────────────────────────────────────────────────

  @Operation(summary = "This business's identity provider", description = "OWNER or MANAGER.")
  @APIResponse(responseCode = "404", description = "None connected")
  @GET
  @Path("/admin/sso")
  public ApiResponse<SsoDtos.ConnectionView> connection() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        sso.view(ctx.requireTenantId())
            .orElseThrow(
                () ->
                    com.storeql.web.ApiException.notFound(
                        "SSO_NOT_CONFIGURED", "This business has no identity provider")));
  }

  @Operation(
      summary = "Connect this business's identity provider, or change it",
      description =
          "OWNER only. The client secret is written, never read back. A tier listed in"
              + " requiredTiers stops signing in with a password — never OWNER. Changing the issuer"
              + " unlinks everybody linked at the old one.")
  @APIResponse(responseCode = "409", description = "Another business has that sign-in name")
  @PUT
  @Path("/admin/sso")
  public ApiResponse<SsoDtos.ConnectionView> put(SsoDtos.ConnectionRequest req) {
    ctx.requireAnyRole("OWNER");
    Validations.validate(req);
    return ApiResponse.ok(sso.put(ctx.requireTenantId(), ctx.requireUserId(), req));
  }

  @Operation(
      summary = "Disconnect this business's identity provider",
      description = "OWNER only. Staff sign in with their passwords again.")
  @DELETE
  @Path("/admin/sso")
  public ApiResponse<String> delete() {
    ctx.requireAnyRole("OWNER");
    sso.delete(ctx.requireTenantId(), ctx.requireUserId());
    return ApiResponse.ok("removed");
  }

  @Operation(
      summary = "Can staff sign in through the provider?",
      description =
          "OWNER or MANAGER. Each thing that must hold, and what to do when it does not — reading"
              + " the provider's discovery document and keys now. Nothing is signed into.")
  @GET
  @Path("/admin/sso/readiness")
  public ApiResponse<SsoDtos.Readiness> readiness() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(sso.readiness(ctx.requireTenantId()));
  }

  @Operation(
      summary = "Who has signed in through the provider",
      description = "OWNER or MANAGER. Each login and the provider's person it is linked to.")
  @GET
  @Path("/admin/sso/identities")
  public ApiResponse<SsoDtos.LinkedLoginPage> identities(
      @QueryParam("after") String after, @QueryParam("limit") @DefaultValue("20") int limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        sso.identities(
            ctx.requireTenantId(),
            after == null || after.isBlank() ? null : Parsing.uuid(after, "after"),
            Math.max(1, Math.min(limit, 100))));
  }

  @Operation(
      summary = "Unlink a login from the provider's person",
      description =
          "OWNER only. For an address the provider has given to someone else: the login is"
              + " matched by email again at its next sign-in through the provider.")
  @APIResponse(responseCode = "404", description = "Not a link of this business")
  @DELETE
  @Path("/admin/sso/identities/{id}")
  public ApiResponse<String> unlink(@PathParam("id") String id) {
    ctx.requireAnyRole("OWNER");
    sso.unlink(ctx.requireTenantId(), Parsing.uuid(id, "id"), ctx.requireUserId());
    return ApiResponse.ok("unlinked");
  }
}
