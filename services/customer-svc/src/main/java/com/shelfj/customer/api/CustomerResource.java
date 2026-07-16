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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

  @Operation(
      summary = "Register a new customer",
      description = "Creates a customer profile for the caller's tenant.")
  @APIResponse(responseCode = "201", description = "Customer created")
  @APIResponse(responseCode = "409", description = "A customer with this email already exists")
  @Tag(name = "Customers")
  @POST
  public Response register(RegisterCustomerRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var customer = service.register(tenantId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toCustomer(customer), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(
      summary = "List customers",
      description = "Cursor-paginated list of customers for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "Page of customers")
  @Tag(name = "Customers")
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

  @Operation(
      summary = "Look up a customer by email or phone",
      description = "POS barcode/QR-code lookup. One of email or phone must be provided.")
  @APIResponse(responseCode = "200", description = "Customer found")
  @APIResponse(responseCode = "400", description = "Neither email nor phone was provided")
  @APIResponse(responseCode = "404", description = "No customer with that email or phone")
  @Tag(name = "Customers")
  @GET
  @Path("/lookup")
  public ApiResponse<?> lookup(
      @QueryParam("email") String email, @QueryParam("phone") String phone) {
    UUID tenantId = ctx.requireTenantId();
    var customer = service.lookup(tenantId, email, phone);
    return ApiResponse.ok(Mappers.toCustomer(customer), ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Get a customer by id",
      description =
          "Staff may read any customer in their tenant; a customer may only read their own"
              + " record.")
  @APIResponse(responseCode = "200", description = "Customer found")
  @APIResponse(responseCode = "404", description = "Customer not found, or not owned by the caller")
  @Tag(name = "Customers")
  @GET
  @Path("/{id}")
  public ApiResponse<?> get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toCustomer(service.get(tenantId, id, ctx)), ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Update a customer", description = "Updates the customer's profile fields.")
  @APIResponse(responseCode = "200", description = "Customer updated")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @APIResponse(responseCode = "409", description = "Customer has been anonymized")
  @Tag(name = "Customers")
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
  @Operation(
      summary = "Anonymize a customer (GDPR erasure)",
      description =
          "Erases PII in place while retaining the record for audit. Destructive and"
              + " hard-to-reverse; requires PLATFORM_ADMIN, OWNER, or MANAGER.")
  @APIResponse(responseCode = "204", description = "Customer anonymized")
  @APIResponse(responseCode = "403", description = "Caller lacks an admin/owner/manager role")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @Tag(name = "Customers")
  @DELETE
  @Path("/{id}")
  public Response anonymize(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    service.anonymize(tenantId, id);
    return Response.noContent().build();
  }

  // ── addresses ─────────────────────────────────────────────────────────────

  @Operation(
      summary = "Add a customer address",
      description = "Adds an address to the customer's address book.")
  @APIResponse(responseCode = "201", description = "Address added")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @Tag(name = "Addresses")
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

  @Operation(
      summary = "List a customer's addresses",
      description =
          "Staff may read any customer's addresses in their tenant; a customer may only read"
              + " their own.")
  @APIResponse(responseCode = "200", description = "Addresses for the customer")
  @APIResponse(responseCode = "404", description = "Customer not found, or not owned by the caller")
  @Tag(name = "Addresses")
  @GET
  @Path("/{id}/addresses")
  public ApiResponse<?> listAddresses(@PathParam("id") UUID customerId) {
    UUID tenantId = ctx.requireTenantId();
    var addresses =
        service.listAddresses(tenantId, customerId, ctx).stream()
            .map(Mappers::toAddress)
            .collect(Collectors.toList());
    return ApiResponse.ok(addresses, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Update a customer address", description = "Replaces an existing address.")
  @APIResponse(responseCode = "200", description = "Address updated")
  @APIResponse(responseCode = "404", description = "Address not found")
  @Tag(name = "Addresses")
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

  @Operation(summary = "Delete a customer address", description = "Removes an address.")
  @APIResponse(responseCode = "204", description = "Address deleted")
  @APIResponse(responseCode = "404", description = "Address not found")
  @Tag(name = "Addresses")
  @DELETE
  @Path("/{id}/addresses/{addressId}")
  public Response deleteAddress(
      @PathParam("id") UUID customerId, @PathParam("addressId") UUID addressId) {
    UUID tenantId = ctx.requireTenantId();
    service.deleteAddress(tenantId, customerId, addressId);
    return Response.noContent().build();
  }

  // ── loyalty ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "Get a customer's loyalty account",
      description =
          "Points balance and tier. Returns a zero-balance BRONZE account if none exists yet.")
  @APIResponse(responseCode = "200", description = "Loyalty account")
  @APIResponse(responseCode = "404", description = "Customer not found, or not owned by the caller")
  @Tag(name = "Loyalty")
  @GET
  @Path("/{id}/loyalty")
  public ApiResponse<?> getLoyalty(@PathParam("id") UUID customerId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.getLoyaltyAccount(tenantId, customerId, ctx)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Earn loyalty points",
      description = "Manually awards points to the customer's loyalty ledger (append-only).")
  @APIResponse(responseCode = "200", description = "Points awarded")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @Tag(name = "Loyalty")
  @POST
  @Path("/{id}/loyalty/earn")
  public ApiResponse<?> earnPoints(@PathParam("id") UUID customerId, EarnPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.earnPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Redeem loyalty points",
      description = "Deducts points from the customer's balance and records a ledger entry.")
  @APIResponse(responseCode = "200", description = "Points redeemed")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @APIResponse(responseCode = "422", description = "Insufficient loyalty points")
  @Tag(name = "Loyalty")
  @POST
  @Path("/{id}/loyalty/redeem")
  public ApiResponse<?> redeemPoints(@PathParam("id") UUID customerId, RedeemPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.redeemPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Adjust loyalty points",
      description =
          "Manual correction (positive or negative) to the customer's points balance; recorded"
              + " in the ledger.")
  @APIResponse(responseCode = "200", description = "Points adjusted")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @Tag(name = "Loyalty")
  @POST
  @Path("/{id}/loyalty/adjust")
  public ApiResponse<?> adjustPoints(@PathParam("id") UUID customerId, AdjustPointsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toLoyalty(service.adjustPoints(tenantId, customerId, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Get the loyalty ledger",
      description = "Append-only history of loyalty earn/redeem/adjust entries, most recent first.")
  @APIResponse(responseCode = "200", description = "Ledger entries")
  @APIResponse(responseCode = "404", description = "Customer not found, or not owned by the caller")
  @Tag(name = "Loyalty")
  @GET
  @Path("/{id}/loyalty/ledger")
  public ApiResponse<?> getLedger(
      @PathParam("id") UUID customerId, @QueryParam("limit") @DefaultValue("50") int limit) {
    UUID tenantId = ctx.requireTenantId();
    var entries =
        service.getLedger(tenantId, customerId, limit, ctx).stream()
            .map(Mappers::toLedgerEntry)
            .collect(Collectors.toList());
    return ApiResponse.ok(entries, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── store credit ──────────────────────────────────────────────────────────

  @Operation(
      summary = "Get a customer's store-credit balance",
      description = "Returns a zero balance if no account exists yet for the given currency.")
  @APIResponse(responseCode = "200", description = "Store-credit account")
  @APIResponse(responseCode = "404", description = "Customer not found, or not owned by the caller")
  @Tag(name = "Store Credit")
  @GET
  @Path("/{id}/store-credit")
  public ApiResponse<?> getStoreCredit(
      @PathParam("id") UUID customerId,
      @QueryParam("currency") @DefaultValue("GBP") String currency) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toStoreCredit(service.getStoreCredit(tenantId, customerId, currency, ctx)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Issue store credit",
      description = "Adds to the customer's store-credit balance; recorded in the ledger.")
  @APIResponse(responseCode = "200", description = "Store credit issued")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @Tag(name = "Store Credit")
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

  @Operation(
      summary = "Redeem store credit",
      description =
          "Deducts from the customer's store-credit balance; idempotent per order when an"
              + " orderId is supplied. Recorded in the ledger.")
  @APIResponse(responseCode = "200", description = "Store credit redeemed")
  @APIResponse(responseCode = "404", description = "Customer not found")
  @APIResponse(responseCode = "422", description = "Insufficient store credit")
  @Tag(name = "Store Credit")
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
