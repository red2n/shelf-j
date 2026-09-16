package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.CurrencyRepublishResponse;
import com.shelfj.tenant.dto.Dtos.PatchStatusRequest;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Platform-admin endpoints — PLATFORM_ADMIN role required on every method. */
@Path("/platform")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Platform")
public class PlatformResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  /**
   * Cross-tenant list of every business on the platform.
   *
   * <p>One of the few reads that deliberately crosses tenant boundaries, so it is gated on {@code
   * PLATFORM_ADMIN} rather than an ordinary tenant role.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of tenants, with the next cursor in {@code meta}
   * @throws com.shelfj.web.ApiException {@code 403} when the caller is not a {@code PLATFORM_ADMIN}
   */
  @Operation(
      summary = "List all tenants",
      description =
          "Cross-tenant platform-admin list. Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100."
              + " Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @GET
  @Path("/tenants")
  public ApiResponse<List<TenantResponse>> listAllTenants(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    var page = service.listAllTenants(after, Cursor.clampLimit(limit));
    var tenants = page.items().stream().map(Mappers::toTenant).toList();
    return ApiResponse.ok(tenants, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * The business a network's delivery lands with (07.13, the transport seam).
   *
   * <p>purchase-svc asks this, platform-wide, when an access point delivers an e-invoice: the
   * document names its buyer by electronic address and VAT identifier, and only one active business
   * may hold what it names.
   *
   * @param scheme the address's EAS scheme, with {@code id}
   * @param id the identifier within the scheme
   * @param vatNumber the buyer's VAT identifier, read when no address is given
   * @return the business
   * @throws com.shelfj.web.ApiException {@code 400} for half an address or nothing named; {@code
   *     403} when the caller is not a {@code PLATFORM_ADMIN}; {@code 404} when no active business
   *     holds it; {@code 409} when more than one does
   */
  @Operation(
      summary = "Which business holds an e-invoicing address",
      description =
          "By scheme and id (a Peppol participant identifier), else by vatNumber. The one active"
              + " business holding it; 404 when none does, 409 when more than one does, since a"
              + " delivery to a shared address lands nowhere. Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "400", description = "Half an address, or nothing named")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "No active business holds it")
  @APIResponse(responseCode = "409", description = "More than one active business holds it")
  @GET
  @Path("/tenants/by-einvoice-address")
  public ApiResponse<TenantResponse> receiver(
      @QueryParam("scheme") String scheme,
      @QueryParam("id") String id,
      @QueryParam("vatNumber") String vatNumber) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(Mappers.toTenant(service.receiver(scheme, id, vatNumber)));
  }

  /**
   * Suspends or reactivates a tenant.
   *
   * <p>Publishes {@code TenantStatusChanged} so the effect reaches the services that must act on it
   * — iam-svc locking staff out, cart/order-svc refusing trade — rather than only flipping a row
   * here.
   *
   * @param tenantId the tenant whose status to change; taken from the path, as this is a
   *     cross-tenant platform operation
   * @param req the new status, {@code ACTIVE} or {@code INACTIVE}
   * @return the tenant with its new status
   * @throws com.shelfj.web.ApiException {@code 400} when the status is neither; {@code 403} when
   *     the caller is not a {@code PLATFORM_ADMIN}; {@code 404} when the tenant does not exist
   */
  @Operation(
      summary = "Suspend or reactivate a tenant",
      description =
          "Sets a tenant's status to ACTIVE or INACTIVE and publishes TenantStatusChanged so other"
              + " services (e.g. iam-svc locking out staff) can react. Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "400", description = "status must be ACTIVE or INACTIVE")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @PATCH
  @Path("/tenants/{tenantId}/status")
  public ApiResponse<TenantResponse> patchTenantStatus(
      @PathParam("tenantId") UUID tenantId, PatchStatusRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.patchTenantStatus(tenantId, req)));
  }

  /**
   * Re-publishes {@code TenantCurrencyDeclared} so downstream projections can be rebuilt.
   *
   * <p>A repair tool, not a migration: a tenant onboarded before a consumer existed has no currency
   * projection there, and that consumer silently falls back to a platform default — so the tenant
   * trades in the wrong currency with nothing to signal it. Safe to run repeatedly, because each
   * replay carries fresh event ids and is therefore re-applied rather than deduped away.
   *
   * @param tenantId one tenant to re-announce, or {@code null} for every tenant
   * @return how many tenants were announced; zero when the named tenant has no currency recorded
   * @throws com.shelfj.web.ApiException {@code 400} when {@code tenantId} is not a UUID; {@code
   *     403} when the caller is not a {@code PLATFORM_ADMIN}
   */
  @Operation(
      summary = "Re-announce tenants' declared currencies",
      description =
          "Publishes TenantCurrencyDeclared for every tenant that has a currency recorded, or for"
              + " one tenant with ?tenantId=. Consumers keep a local projection of this so they can"
              + " stamp money-bearing rows without calling this service; a tenant onboarded before"
              + " such a consumer existed has no projection and silently falls back to a default"
              + " currency. This is how that is repaired. Safe to run repeatedly. Requires"
              + " PLATFORM_ADMIN.")
  @APIResponse(responseCode = "200", description = "Number of tenants announced")
  @APIResponse(responseCode = "400", description = "tenantId is not a UUID")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @POST
  @Path("/tenants/republish-currency")
  public ApiResponse<CurrencyRepublishResponse> republishCurrency(
      @QueryParam("tenantId") String tenantId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    int announced = service.republishTenantCurrencies(Parsing.optionalUuid(tenantId, "tenantId"));
    return ApiResponse.ok(new CurrencyRepublishResponse(announced));
  }
}
