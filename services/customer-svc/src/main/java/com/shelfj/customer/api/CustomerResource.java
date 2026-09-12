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

  /**
   * Registers a customer profile for the caller's tenant.
   *
   * @param req the email, name and optional phone, date of birth, gender and GDPR consent
   * @return {@code 201} with the created customer
   * @throws com.shelfj.web.ApiException {@code 409} when that email is already registered here
   */
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

  /**
   * Cursor-paginated list of the tenant's customers.
   *
   * @param after cursor — the {@code nextCursor} from the previous page, or {@code null} to start
   * @param limit page size; capped at 100
   * @return the page plus a {@code nextCursor}, which is {@code null} on the last page
   */
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

  /**
   * POS barcode/QR-code lookup by email or phone.
   *
   * <p>Email wins when both are supplied.
   *
   * @param email the email to match, case-insensitively, or {@code null}
   * @param phone the phone to match, or {@code null}
   * @return the matching customer
   * @throws com.shelfj.web.ApiException {@code 400} when neither is supplied; {@code 404} when
   *     nothing matches
   */
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

  /**
   * Reads one customer.
   *
   * @param id the customer to read
   * @return the customer
   * @throws com.shelfj.web.ApiException {@code 404} when no such customer exists in the tenant or
   *     the caller may not read it — a denial is a 404 so ids cannot be probed for existence
   */
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

  /**
   * Updates a customer's profile fields.
   *
   * <p>Email is not changeable here — it identifies the record.
   *
   * @param id the customer to update
   * @param req the replacement name, phone, date of birth, gender and consent flag
   * @return the updated customer
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist; {@code 409}
   *     when it has already been anonymized
   */
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
   *
   * @param id the customer to erase
   * @return {@code 204} with no body
   * @throws com.shelfj.web.ApiException {@code 403} without an admin/owner/manager role; {@code
   *     404} when the customer does not exist
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

  /**
   * Adds an address to the customer's address book.
   *
   * @param customerId the customer to add the address to
   * @param req the address lines and optional type, defaulting to {@code HOME}
   * @return {@code 201} with the created address
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist
   */
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

  /**
   * Lists a customer's addresses.
   *
   * @param customerId the customer whose addresses to list
   * @return the addresses, empty when none are on file
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist or the caller
   *     may not read it
   */
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

  /**
   * Replaces an existing address in full.
   *
   * <p>Every field is overwritten, so a field the caller omits is cleared rather than preserved.
   *
   * @param customerId the customer the address belongs to
   * @param addressId the address to replace
   * @param req the replacement address
   * @return the updated address
   * @throws com.shelfj.web.ApiException {@code 404} when the address does not exist or belongs to a
   *     different customer
   */
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

  /**
   * Removes an address from the customer's address book.
   *
   * @param customerId the customer the address belongs to
   * @param addressId the address to delete
   * @return {@code 204} with no body
   * @throws com.shelfj.web.ApiException {@code 404} when the address does not exist or belongs to a
   *     different customer
   */
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

  /**
   * Reads a customer's points balance and tier.
   *
   * <p>A customer who has never earned points gets a zero-balance BRONZE account rather than a 404.
   *
   * @param customerId the customer whose account to read
   * @return the loyalty account
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist or the caller
   *     may not read it
   */
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

  /**
   * Manually awards points to the customer's loyalty ledger (append-only).
   *
   * <p>Not idempotent — calling it twice awards twice. Points for a confirmed order accrue
   * automatically from the {@code OrderConfirmed} event instead.
   *
   * @param customerId the customer to credit
   * @param req the points, an optional originating order and a reason for the ledger
   * @return the account with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist
   */
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

  /**
   * Spends points from the customer's balance and records a ledger entry.
   *
   * <p>Lifetime points are untouched, so redeeming never demotes a customer's tier.
   *
   * @param customerId the customer to debit
   * @param req the points, an optional order being paid towards and a reason for the ledger
   * @return the account with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist; {@code 422}
   *     when the balance is insufficient
   */
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

  /**
   * Applies a signed manual correction to the customer's points balance.
   *
   * <p>Negative values remove points. This is the goodwill/correction path, distinct from earn and
   * redeem.
   *
   * @param customerId the customer whose balance to correct
   * @param req the signed point delta and a reason for the ledger
   * @return the account with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist
   */
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

  /**
   * Append-only history of loyalty earn/redeem/adjust entries, most recent first.
   *
   * @param customerId the customer whose ledger to read
   * @param limit page size; capped at 100
   * @return the ledger entries, newest first
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist or the caller
   *     may not read it
   */
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

  /**
   * Reads a customer's store-credit balance in one currency.
   *
   * <p>Balances are held per currency; a customer with none in that currency gets a zero balance
   * rather than a 404.
   *
   * @param customerId the customer whose balance to read
   * @param currency ISO-4217 code, defaulting to {@code GBP}
   * @return the store-credit account
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist or the caller
   *     may not read it
   */
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

  /**
   * Adds to the customer's store-credit balance and records a ledger entry.
   *
   * <p>Not idempotent — calling it twice issues twice.
   *
   * @param customerId the customer to credit
   * @param req the amount, optional currency (defaults to GBP), originating order and reason
   * @return the account with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist
   */
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

  /**
   * Deducts from the customer's store-credit balance and records a ledger entry.
   *
   * <p>Idempotent per order when an {@code orderId} is supplied, which is what lets payment-svc
   * retry a store-credit tender safely.
   *
   * @param customerId the customer to debit
   * @param req the amount, optional currency (defaults to GBP), order being paid and reason
   * @return the account with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when the customer does not exist; {@code 422}
   *     when the balance is insufficient
   */
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
