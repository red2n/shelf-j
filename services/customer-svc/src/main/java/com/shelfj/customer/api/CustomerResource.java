package com.shelfj.customer.api;

import com.shelfj.customer.dto.Dtos.AddAddressRequest;
import com.shelfj.customer.dto.Dtos.AdjustPointsRequest;
import com.shelfj.customer.dto.Dtos.CustomerListResponse;
import com.shelfj.customer.dto.Dtos.EarnPointsRequest;
import com.shelfj.customer.dto.Dtos.IssueStoreCreditRequest;
import com.shelfj.customer.dto.Dtos.RedeemPointsRequest;
import com.shelfj.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.shelfj.customer.dto.Dtos.RegisterCustomerRequest;
import com.shelfj.customer.dto.Dtos.UpdateCustomerRequest;
import com.shelfj.customer.mapper.Mappers;
import com.shelfj.customer.service.CustomerService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customer profile, address, loyalty, and store-credit endpoints. Controllers are thin: all logic
 * lives in CustomerService (golden rule #9).
 */
@Path("/customers")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource {

  @Inject CustomerService service;
  @Inject TenantContext ctx;

  // ── profile ───────────────────────────────────────────────────────────────

  @POST
  public Response register(RegisterCustomerRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var customer = service.register(tenantId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toCustomer(customer), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  public ApiResponse<CustomerListResponse> list(
      @QueryParam("after") String after, @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenantId = ctx.requireTenantId();
    int cap = Math.min(limit, 100);
    var page = service.list(tenantId, after, cap);
    String nextCursor = page.size() > cap ? page.get(cap - 1).id().toString() : null;
    var items = page.stream().limit(cap).map(Mappers::toCustomer).collect(Collectors.toList());
    return ApiResponse.ok(
        new CustomerListResponse(items, nextCursor), ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/lookup")
  public ApiResponse<?> lookup(
      @QueryParam("email") String email, @QueryParam("phone") String phone) {
    UUID tenantId = ctx.requireTenantId();
    var customer = service.lookup(tenantId, email, phone);
    return ApiResponse.ok(Mappers.toCustomer(customer), ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/{id}")
  public ApiResponse<?> get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toCustomer(service.get(tenantId, id)), ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/{id}")
  public ApiResponse<?> update(@PathParam("id") UUID id, UpdateCustomerRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toCustomer(service.update(tenantId, id, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * GDPR erasure — destructive and hard-to-reverse, unlike the loyalty/store-credit redemption
   * endpoints below which deliberately stay open to any staff role for normal POS checkout use.
   * Restricted to management roles; the shared filter's generic "any staff role" rule would
   * otherwise let a CASHIER erase a customer's PII (this path isn't under {@code /admin/}, so it
   * doesn't get that filter's stricter rule for free).
   */
  @DELETE
  @Path("/{id}")
  public Response anonymize(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    service.anonymize(tenantId, id);
    return Response.noContent().build();
  }

  // ── addresses ─────────────────────────────────────────────────────────────

  @POST
  @Path("/{id}/addresses")
  public Response addAddress(@PathParam("id") UUID customerId, AddAddressRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var address = service.addAddress(tenantId, customerId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toAddress(address), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/{id}/addresses")
  public ApiResponse<?> listAddresses(@PathParam("id") UUID customerId) {
    UUID tenantId = ctx.requireTenantId();
    var addresses =
        service.listAddresses(tenantId, customerId).stream()
            .map(Mappers::toAddress)
            .collect(Collectors.toList());
    return ApiResponse.ok(addresses, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/{id}/addresses/{addressId}")
  public ApiResponse<?> updateAddress(
      @PathParam("id") UUID customerId,
      @PathParam("addressId") UUID addressId,
      AddAddressRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var address = service.updateAddress(tenantId, customerId, addressId, req);
    return ApiResponse.ok(Mappers.toAddress(address), ApiResponse.Meta.of(ctx.requestId()));
  }

  @DELETE
  @Path("/{id}/addresses/{addressId}")
  public Response deleteAddress(
      @PathParam("id") UUID customerId, @PathParam("addressId") UUID addressId) {
    UUID tenantId = ctx.requireTenantId();
    service.deleteAddress(tenantId, customerId, addressId);
    return Response.noContent().build();
  }

  // ── loyalty ───────────────────────────────────────────────────────────────

  @GET
  @Path("/{id}/loyalty")
  public ApiResponse<?> getLoyalty(@PathParam("id") UUID customerId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.getLoyaltyAccount(tenantId, customerId)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/{id}/loyalty/earn")
  public ApiResponse<?> earnPoints(@PathParam("id") UUID customerId, EarnPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.earnPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/{id}/loyalty/redeem")
  public ApiResponse<?> redeemPoints(@PathParam("id") UUID customerId, RedeemPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.redeemPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/{id}/loyalty/adjust")
  public ApiResponse<?> adjustPoints(@PathParam("id") UUID customerId, AdjustPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.adjustPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/{id}/loyalty/ledger")
  public ApiResponse<?> getLedger(
      @PathParam("id") UUID customerId, @QueryParam("limit") @DefaultValue("50") int limit) {
    UUID tenantId = ctx.requireTenantId();
    var entries =
        service.getLedger(tenantId, customerId, limit).stream()
            .map(Mappers::toLedgerEntry)
            .collect(Collectors.toList());
    return ApiResponse.ok(entries, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── store credit ──────────────────────────────────────────────────────────

  @GET
  @Path("/{id}/store-credit")
  public ApiResponse<?> getStoreCredit(
      @PathParam("id") UUID customerId,
      @QueryParam("currency") @DefaultValue("GBP") String currency) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toStoreCredit(service.getStoreCredit(tenantId, customerId, currency)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/{id}/store-credit/issue")
  public ApiResponse<?> issueStoreCredit(
      @PathParam("id") UUID customerId, IssueStoreCreditRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toStoreCredit(service.issueStoreCredit(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/{id}/store-credit/redeem")
  public ApiResponse<?> redeemStoreCredit(
      @PathParam("id") UUID customerId, RedeemStoreCreditRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toStoreCredit(service.redeemStoreCredit(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }
}
