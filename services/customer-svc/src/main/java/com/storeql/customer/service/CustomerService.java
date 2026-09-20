package com.storeql.customer.service;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.CustomerAddress;
import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.Domain.LoyaltyLedgerEntry;
import com.storeql.customer.domain.Domain.MarketingConsentEntry;
import com.storeql.customer.domain.Domain.MarketingPreference;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.dto.Dtos.AddAddressRequest;
import com.storeql.customer.dto.Dtos.AddressResponse;
import com.storeql.customer.dto.Dtos.AdjustPointsRequest;
import com.storeql.customer.dto.Dtos.DataExportResponse;
import com.storeql.customer.dto.Dtos.EarnPointsRequest;
import com.storeql.customer.dto.Dtos.ExportSubject;
import com.storeql.customer.dto.Dtos.IssueStoreCreditRequest;
import com.storeql.customer.dto.Dtos.LoyaltyAccountResponse;
import com.storeql.customer.dto.Dtos.LoyaltyLedgerEntryResponse;
import com.storeql.customer.dto.Dtos.MarketingChannelChoice;
import com.storeql.customer.dto.Dtos.RedeemPointsRequest;
import com.storeql.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.storeql.customer.dto.Dtos.RegisterCustomerRequest;
import com.storeql.customer.dto.Dtos.SetMarketingPreferencesRequest;
import com.storeql.customer.dto.Dtos.StoreCreditAccountResponse;
import com.storeql.customer.dto.Dtos.StoreCreditLedgerEntryResponse;
import com.storeql.customer.dto.Dtos.UpdateCustomerRequest;
import com.storeql.customer.mapper.Mappers;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Core logic for customer profiles, loyalty, and store credit. All writes publish events via the
 * transactional outbox (golden rule #6).
 */
@ApplicationScoped
public class CustomerService {

  /** processed_events consumer key for the order-confirmed → loyalty accrual path (dedupe). */
  public static final String ORDER_CONFIRMED_CONSUMER = "customer-svc/order-confirmed";

  @Inject CustomerRepository repo;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject com.storeql.customer.client.OrderClient orders;
  @Inject MarketingConsentService marketing;

  /** Points awarded per unit of order currency spent (e.g. 1 → 1 point per £1). */
  @Inject
  @ConfigProperty(name = "storeql.customer.loyalty.points-per-unit", defaultValue = "1")
  String pointsPerUnitRaw;

  // ── customer profile ──────────────────────────────────────────────────────

  /**
   * Registers a customer profile and publishes {@code CustomerRegistered}.
   *
   * <p>The email is lower-cased before storage, so uniqueness within a tenant is case-insensitive.
   * This is a shop's own record of a person, distinct from the iam-svc login they may sign in with.
   *
   * @param tenantId owning tenant
   * @param req the email, name and optional phone, date of birth, gender and GDPR consent
   * @return the created customer
   * @throws ApiException {@code CUSTOMER_ALREADY_EXISTS} (409) when that email is already
   *     registered in this tenant
   */
  public Customer register(UUID tenantId, RegisterCustomerRequest req) {
    if (repo.findByEmail(tenantId, req.email()).isPresent()) {
      throw new ApiException(
          409, "CUSTOMER_ALREADY_EXISTS", "A customer with this email already exists", List.of());
    }
    Instant now = Instant.now();
    UUID id = Ids.newId();
    var customer =
        new Customer(
            id,
            tenantId,
            null,
            req.email().toLowerCase(Locale.ROOT),
            req.phone(),
            req.firstName().trim(),
            req.lastName().trim(),
            parseDate(req.dob()),
            req.gender(),
            Customer.STATUS_ACTIVE,
            Boolean.TRUE.equals(req.gdprConsent()) ? now : null,
            null,
            now,
            now);
    String payload =
        Json.createObjectBuilder()
            .add("customerId", id.toString())
            .add("tenantId", tenantId.toString())
            .add("email", req.email())
            .build()
            .toString();
    var event =
        new OutboxRow(
            "CustomerRegistered", "storeql.customer.customer-registered", tenantId, id, payload);
    Customer created = repo.createCustomer(customer, event);
    // The consent tick at signup, recorded where consent lives rather than only as a timestamp on
    // the customer row: PECR asks what the person agreed to and UK GDPR art.7(1) asks for evidence
    // of it, and a single column can answer neither. Written after the customer exists rather than
    // with it — if this fails, the shop has a customer it may not market to, which is the safe way
    // round for the failure to land.
    if (Boolean.TRUE.equals(req.gdprConsent())) {
      marketing.setPreferences(
          tenantId,
          created,
          new SetMarketingPreferencesRequest(
              List.of(
                  new MarketingChannelChoice(
                      MarketingPreference.CHANNEL_EMAIL, true, MarketingPreference.BASIS_CONSENT)),
              "Marketing consent given when the customer record was created"),
          MarketingConsentEntry.SOURCE_SIGNUP,
          null);
    }
    return created;
  }

  /**
   * Returns the customer record the signed-in shopper owns in this tenant, creating it on first use
   * (SJ-D44).
   *
   * <p>A login is global and a shop's customer record is not, so until a shopper buys somewhere,
   * the shop holds nothing about them. This is the join: it runs at checkout, so the order carries
   * a customer id the shop can actually resolve — which is what lets loyalty award points for an
   * online order, the confirmation email find an address, and an erasure reach what the order
   * holds.
   *
   * <p>The identity comes from the verified token, never the request: the caller may ask only for
   * their own record.
   *
   * @param tenantId owning tenant
   * @param loginId the authenticated login, from the token
   * @param email that login's own email, from the token
   * @return the linked customer record, whether it already existed or was created here
   * @throws ApiException {@code CUSTOMER_LOGIN_UNIDENTIFIED} (400) when the token carried no email,
   *     since a record with no way to reach the person is worse than none
   */
  public Customer linkLogin(UUID tenantId, UUID loginId, String email) {
    if (email == null || email.isBlank()) {
      throw ApiException.badRequest(
          "CUSTOMER_LOGIN_UNIDENTIFIED", "the token carries no email to identify the shopper by");
    }
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    UUID newId = Ids.newId();
    String payload =
        Json.createObjectBuilder()
            .add("customerId", newId.toString())
            .add("tenantId", tenantId.toString())
            .add("email", normalized)
            .build()
            .toString();
    var event =
        new OutboxRow(
            "CustomerRegistered", "storeql.customer.customer-registered", tenantId, newId, payload);
    return repo.linkLogin(tenantId, loginId, normalized, newId, event);
  }

  /**
   * The customer record a login owns in this tenant, if there is one.
   *
   * @param tenantId owning tenant
   * @param loginId the authenticated login, from the token
   * @return the linked customer record, or empty when this tenant holds none
   */
  public java.util.Optional<Customer> findByLogin(UUID tenantId, UUID loginId) {
    return repo.findByLogin(tenantId, loginId);
  }

  /**
   * Reads the customer record a login owns in this tenant, without creating one.
   *
   * @param tenantId owning tenant
   * @param loginId the authenticated login, from the token
   * @return the linked customer record
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when this tenant holds no record for that
   *     login
   */
  public Customer getByLogin(UUID tenantId, UUID loginId) {
    return repo.findByLogin(tenantId, loginId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  /** Hard cap on the ledgers an export carries, so one request cannot read an unbounded table. */
  private static final int EXPORT_LEDGER_LIMIT = 5000;

  /**
   * Everything this shop holds about one person, in one machine-readable document (UK GDPR art.20 —
   * the right to receive your data in a structured, commonly used, machine-readable format).
   *
   * <p>Assembled, not stored. The profile, addresses, loyalty and store credit are this service's;
   * the orders belong to order-svc and are asked for (golden rule #1). Both ids are passed on,
   * because a person's purchases are split across them — online sales under the login, till sales
   * under the customer record (SJ-D44) — and an export that carried half of somebody's history
   * while claiming to be their data would be a false answer rather than a thin one.
   *
   * <p>A person with no customer record still gets an export: they may have bought as a signed-in
   * shopper before the shop ever wrote one down.
   *
   * @param tenantId owning tenant
   * @param customer the shop's record of the person, or {@code null} when it holds none
   * @param loginId the login they sign in with, or {@code null}
   * @param loginEmail the email on that login, used when there is no customer record to take one
   *     from
   * @return the assembled export
   * @throws ApiException {@code EXPORT_NO_SUBJECT} (400) when neither a customer nor a login was
   *     given; {@code EXPORT_ORDERS_UNAVAILABLE} (503) when order-svc could not be reached
   */
  public DataExportResponse export(
      UUID tenantId, Customer customer, UUID loginId, String loginEmail) {
    UUID customerId = customer == null ? null : customer.id();
    if (customerId == null && loginId == null) {
      throw ApiException.badRequest("EXPORT_NO_SUBJECT", "no customer and no login to export");
    }
    List<AddressResponse> addresses =
        customerId == null
            ? List.of()
            : repo.listAddresses(tenantId, customerId).stream().map(Mappers::toAddress).toList();
    LoyaltyAccountResponse loyalty =
        customerId == null
            ? null
            : repo.findLoyaltyAccount(tenantId, customerId).map(Mappers::toLoyalty).orElse(null);
    List<LoyaltyLedgerEntryResponse> loyaltyLedger =
        customerId == null
            ? List.of()
            : repo.listLedger(tenantId, customerId, EXPORT_LEDGER_LIMIT).stream()
                .map(Mappers::toLedgerEntry)
                .toList();
    List<StoreCreditAccountResponse> credit =
        customerId == null
            ? List.of()
            : repo.listStoreCreditAccounts(tenantId, customerId).stream()
                .map(Mappers::toStoreCredit)
                .toList();
    List<StoreCreditLedgerEntryResponse> creditLedger =
        customerId == null
            ? List.of()
            : repo.listStoreCreditLedger(tenantId, customerId, EXPORT_LEDGER_LIMIT).stream()
                .map(Mappers::toStoreCreditEntry)
                .toList();

    List<com.storeql.customer.dto.Dtos.MarketingPreferenceResponse> preferences =
        customerId == null
            ? List.of()
            : marketing.preferences(tenantId, customerId).stream()
                .map(Mappers::toMarketingPreference)
                .toList();
    List<com.storeql.customer.dto.Dtos.MarketingConsentEntryResponse> consentLog =
        customerId == null
            ? List.of()
            : marketing.consentLog(tenantId, customerId).stream()
                .map(Mappers::toMarketingConsentEntry)
                .toList();

    return new DataExportResponse(
        Instant.now().toString(),
        tenantId.toString(),
        new ExportSubject(
            customerId == null ? null : customerId.toString(),
            loginId == null ? null : loginId.toString(),
            customer != null ? customer.email() : loginEmail),
        customer == null ? null : Mappers.toCustomer(customer),
        addresses,
        loyalty,
        loyaltyLedger,
        credit,
        creditLedger,
        preferences,
        consentLog,
        ordersOf(tenantId, customerId, loginId));
  }

  /**
   * The orders half of the export, with every way of not getting them turned into one refusal.
   *
   * <p>An open circuit throws {@code CircuitBreakerOpenException} from the interceptor, before the
   * client's own method body runs, so the client cannot catch it — and left alone it would surface
   * as a 500 saying nothing. Either way the answer is the same: the shop cannot produce a complete
   * export right now, and must say so rather than serve a partial one.
   */
  private jakarta.json.JsonArray ordersOf(UUID tenantId, UUID customerId, UUID loginId) {
    try {
      return orders.ordersOf(tenantId, customerId, loginId);
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ApiException(
          503,
          "EXPORT_ORDERS_UNAVAILABLE",
          "order-svc could not be reached, so the export would be incomplete",
          List.of(),
          e);
    }
  }

  /**
   * Reads a customer with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>For internal callers only — anything serving a request should use {@link #get(UUID, UUID,
   * TenantContext)} so one customer cannot read another's record.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @return the customer
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public Customer get(UUID tenantId, UUID customerId) {
    return repo.findById(tenantId, customerId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  /**
   * Customer-by-id read for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @param ctx caller context; staff may read anyone in the tenant, a customer only themselves
   * @return the customer
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists or the
   *     caller may not read it — denials are 404 so ids cannot be probed for existence
   */
  public Customer get(UUID tenantId, UUID customerId, TenantContext ctx) {
    requireReadAccess(customerId, ctx);
    return get(tenantId, customerId);
  }

  /**
   * Object-level authorization for customer-scoped reads (mirrors OrderService.requireReadAccess):
   * a customer id in the path is not proof of ownership. Staff may read any customer in their
   * tenant; an authenticated customer may only read their own record. Denials are 404 (not 403) so
   * customer ids can't be probed for existence.
   *
   * <p>There is deliberately no exemption for a caller with no principal. That branch assumed the
   * gateway never forwards a tenant here without a verified user; a guest storefront request does.
   * The filter now denies these paths outright, and the internal callers (notification-svc
   * CustomerClient, payment-svc CustomerClient) stamp a staff role — so if this shape is ever
   * allowlisted for an account self-service screen, it does not reopen with it.
   */
  private static void requireReadAccess(UUID customerId, TenantContext ctx) {
    if (isStaff(ctx)) return;
    // No service-to-service exemption. This used to return early for a caller with no principal
    // at all, on the reasoning that only the mesh could produce that shape — but a guest storefront
    // request carries a tenant and no principal too, so the shape was reachable from outside and
    // any id could be read by anyone who had one. The internal callers now stamp a staff role
    // (payment-svc OrderClient, notification-svc CustomerClient), so nothing needs the exemption.
    if (!customerId.equals(ctx.userId()))
      throw ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found");
  }

  private static boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("PLATFORM_ADMIN")
        || ctx.hasRole("OWNER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("CASHIER");
  }

  /**
   * Lists a tenant's customers, one cursor page at a time.
   *
   * @param tenantId owning tenant
   * @param afterId cursor — the last id from the previous page, or {@code null} to start
   * @param limit page size; silently capped at 100
   * @return the page of customers
   */
  public List<Customer> list(UUID tenantId, String afterId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listCustomers(tenantId, afterId, cap);
  }

  /**
   * Updates a customer's mutable profile fields.
   *
   * <p>Email is not changeable here — it identifies the record. GDPR consent is sticky: an existing
   * consent timestamp is preserved when the request does not re-assert it.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to update
   * @param req the replacement name, phone, date of birth, gender and consent flag
   * @return the updated customer
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public Customer update(UUID tenantId, UUID customerId, UpdateCustomerRequest req) {
    Customer existing = get(tenantId, customerId);
    Instant now = Instant.now();
    Instant gdprConsent = Boolean.TRUE.equals(req.gdprConsent()) ? now : existing.gdprConsentAt();
    var updated =
        new Customer(
            existing.id(),
            tenantId,
            existing.loginId(),
            existing.email(),
            req.phone(),
            req.firstName().trim(),
            req.lastName().trim(),
            parseDate(req.dob()),
            req.gender(),
            existing.status(),
            gdprConsent,
            existing.anonymizedAt(),
            existing.createdAt(),
            now);
    return repo.updateCustomer(updated);
  }

  /**
   * GDPR right-to-erasure: anonymizes PII in place; retains the record for audit.
   *
   * <p>The {@code CustomerErased} event carries ids only — it outlives its handling in the outbox
   * and on the topic, so it must not carry the email or phone it exists to erase.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to erase
   * @return the anonymized record
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public Customer anonymize(UUID tenantId, UUID customerId) {
    Customer existing = get(tenantId, customerId);
    // Ids only. The event outlives its handling — in the outbox and on the topic — so it must not
    // carry the email or phone it exists to erase.
    //
    // The login goes with it when the record has one: this shop's orders file an online sale under
    // the shopper's login, so an erasure that named only the customer id could not reach the
    // delivery name, phone and address on exactly the orders that carry them (SJ-D44).
    var builder =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "CustomerErased")
            .add("tenantId", tenantId.toString())
            .add("customerId", customerId.toString())
            .add("occurredAt", Instant.now().toString());
    if (existing.loginId() != null) {
      builder.add("loginId", existing.loginId().toString());
    }
    String payload = builder.build().toString();
    return repo.anonymize(
        tenantId,
        customerId,
        new OutboxRow(
            "CustomerErased", "storeql.customer.customer-erased", tenantId, customerId, payload));
  }

  /**
   * POS barcode / QR code lookup by email or phone.
   *
   * <p>Email wins when both are supplied.
   *
   * @param tenantId owning tenant
   * @param email the email to match, case-insensitively, or {@code null}
   * @param phone the phone to match, or {@code null}
   * @return the matching customer
   * @throws ApiException {@code LOOKUP_PARAM_REQUIRED} (400) when neither is supplied; {@code
   *     CUSTOMER_NOT_FOUND} (404) when nothing matches
   */
  public Customer lookup(UUID tenantId, String email, String phone) {
    if (email != null && !email.isBlank()) {
      return repo.findByEmail(tenantId, email.toLowerCase(Locale.ROOT))
          .orElseThrow(
              () -> ApiException.notFound("CUSTOMER_NOT_FOUND", "No customer with that email"));
    }
    if (phone != null && !phone.isBlank()) {
      return repo.findByPhone(tenantId, phone)
          .orElseThrow(
              () -> ApiException.notFound("CUSTOMER_NOT_FOUND", "No customer with that phone"));
    }
    throw new ApiException(400, "LOOKUP_PARAM_REQUIRED", "Provide email or phone", List.of());
  }

  // ── addresses ─────────────────────────────────────────────────────────────

  /**
   * Adds a delivery or billing address to a customer.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to add the address to
   * @param req the address lines and optional type, defaulting to {@code HOME}
   * @return the created address
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public CustomerAddress addAddress(UUID tenantId, UUID customerId, AddAddressRequest req) {
    get(tenantId, customerId);
    String type =
        req.type() == null || req.type().isBlank() ? CustomerAddress.TYPE_HOME : req.type();
    var address =
        new CustomerAddress(
            Ids.newId(),
            tenantId,
            customerId,
            type,
            req.line1(),
            req.line2(),
            req.city(),
            req.state(),
            req.country(),
            req.pincode(),
            Boolean.TRUE.equals(req.isDefault()),
            Instant.now());
    return repo.createAddress(address);
  }

  /**
   * Lists a customer's addresses, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>For internal callers only — request-serving code should use the {@link TenantContext}
   * overload.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose addresses to list
   * @return the addresses, empty when none are on file
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public List<CustomerAddress> listAddresses(UUID tenantId, UUID customerId) {
    get(tenantId, customerId);
    return repo.listAddresses(tenantId, customerId);
  }

  /**
   * Lists a customer's addresses for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose addresses to list
   * @param ctx caller context; staff may read anyone in the tenant, a customer only themselves
   * @return the addresses, empty when none are on file
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists or the
   *     caller may not read it
   */
  public List<CustomerAddress> listAddresses(UUID tenantId, UUID customerId, TenantContext ctx) {
    requireReadAccess(customerId, ctx);
    return listAddresses(tenantId, customerId);
  }

  /**
   * Replaces an existing address in full.
   *
   * <p>Every field is overwritten from the request, so a field the caller omits is cleared rather
   * than preserved.
   *
   * @param tenantId owning tenant
   * @param customerId the customer the address belongs to
   * @param addressId the address to replace
   * @param req the replacement address
   * @return the updated address
   * @throws ApiException {@code ADDRESS_NOT_FOUND} (404) when the address does not exist or belongs
   *     to a different customer
   */
  public CustomerAddress updateAddress(
      UUID tenantId, UUID customerId, UUID addressId, AddAddressRequest req) {
    repo.findAddress(tenantId, customerId, addressId)
        .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found"));
    var updated =
        new CustomerAddress(
            addressId,
            tenantId,
            customerId,
            req.type(),
            req.line1(),
            req.line2(),
            req.city(),
            req.state(),
            req.country(),
            req.pincode(),
            Boolean.TRUE.equals(req.isDefault()),
            Instant.now());
    return repo.updateAddress(updated);
  }

  /**
   * Deletes one of a customer's addresses.
   *
   * @param tenantId owning tenant
   * @param customerId the customer the address belongs to
   * @param addressId the address to delete
   * @throws ApiException {@code ADDRESS_NOT_FOUND} (404) when the address does not exist or belongs
   *     to a different customer
   */
  public void deleteAddress(UUID tenantId, UUID customerId, UUID addressId) {
    repo.findAddress(tenantId, customerId, addressId)
        .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found"));
    repo.deleteAddress(tenantId, customerId, addressId);
  }

  // ── the shopper's own profile and address book (12.10) ────────────────────

  /** How many addresses one shopper may keep. A shop's address book is not a free-text store. */
  static final int MAX_ADDRESSES = 10;

  /**
   * Updates the signed-in shopper's own profile. The record is found by the token's login, so a
   * caller can reach no profile but their own; the email is the login's and is not writable here.
   *
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when this shop holds no record for the
   *     login — the shopper claims one first with {@code POST /customers/me}
   */
  public Customer updateMine(UUID tenantId, UUID loginId, UpdateCustomerRequest req) {
    return update(tenantId, getByLogin(tenantId, loginId).id(), req);
  }

  /** The signed-in shopper's own address book. */
  public List<CustomerAddress> listMyAddresses(UUID tenantId, UUID loginId) {
    return listAddresses(tenantId, getByLogin(tenantId, loginId).id());
  }

  /**
   * Adds an address to the signed-in shopper's own book, capped at {@link #MAX_ADDRESSES}. The cap
   * is decided under the customer's row lock, so two adds racing for the last place cannot both
   * take it.
   *
   * @throws ApiException {@code CUSTOMER_ADDRESS_LIMIT} (409) when the book is full
   */
  public CustomerAddress addMyAddress(UUID tenantId, UUID loginId, AddAddressRequest req) {
    Customer mine = getByLogin(tenantId, loginId);
    String type =
        req.type() == null || req.type().isBlank() ? CustomerAddress.TYPE_HOME : req.type();
    var address =
        new CustomerAddress(
            Ids.newId(),
            tenantId,
            mine.id(),
            type,
            req.line1(),
            req.line2(),
            req.city(),
            req.state(),
            req.country(),
            req.pincode(),
            Boolean.TRUE.equals(req.isDefault()),
            Instant.now());
    return repo.createAddressCapped(address, MAX_ADDRESSES);
  }

  /** Replaces one of the signed-in shopper's own addresses; another shopper's is not found. */
  public CustomerAddress updateMyAddress(
      UUID tenantId, UUID loginId, UUID addressId, AddAddressRequest req) {
    return updateAddress(tenantId, getByLogin(tenantId, loginId).id(), addressId, req);
  }

  /** Removes one of the signed-in shopper's own addresses; another shopper's is not found. */
  public void deleteMyAddress(UUID tenantId, UUID loginId, UUID addressId) {
    deleteAddress(tenantId, getByLogin(tenantId, loginId).id(), addressId);
  }

  // ── loyalty ───────────────────────────────────────────────────────────────

  /**
   * Reads a customer's loyalty account, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>A customer who has never earned points has no row; a zero-balance BRONZE account is
   * synthesised rather than returning empty, so callers need no special case. That synthetic
   * account is not persisted.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose account to read
   * @return the loyalty account, real or a zero-balance stand-in
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public LoyaltyAccount getLoyaltyAccount(UUID tenantId, UUID customerId) {
    get(tenantId, customerId);
    return repo.findLoyaltyAccount(tenantId, customerId)
        .orElseGet(
            () ->
                new LoyaltyAccount(
                    Ids.newId(),
                    tenantId,
                    customerId,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LoyaltyAccount.TIER_BRONZE,
                    Instant.now(),
                    Instant.now()));
  }

  /**
   * Reads a loyalty account for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose account to read
   * @param ctx caller context; staff may read anyone in the tenant, a customer only themselves
   * @return the loyalty account, real or a zero-balance stand-in
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists or the
   *     caller may not read it
   */
  public LoyaltyAccount getLoyaltyAccount(UUID tenantId, UUID customerId, TenantContext ctx) {
    requireReadAccess(customerId, ctx);
    return getLoyaltyAccount(tenantId, customerId);
  }

  /**
   * Awards loyalty points manually and publishes {@code LoyaltyEarned}.
   *
   * <p>The staff-initiated counterpart to {@link #accrueLoyaltyFromOrder}, and <strong>not</strong>
   * idempotent — calling it twice awards twice.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to credit
   * @param req the points, an optional originating order and a reason for the ledger
   * @return the account with its new balance
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public LoyaltyAccount earnPoints(UUID tenantId, UUID customerId, EarnPointsRequest req) {
    get(tenantId, customerId);
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    var event =
        loyaltyEvent(
            LOYALTY_EARNED, TOPIC_EARNED, tenantId, customerId, req.points(), orderId, null, null);
    return repo.earnPoints(tenantId, customerId, req.points(), orderId, req.reason(), event);
  }

  /**
   * Accrue loyalty points for a confirmed order, driven by the {@code OrderConfirmed} event and
   * idempotent on its {@code eventId}. Points = order total × {@code
   * storeql.customer.loyalty.points-per-unit}, rounded down so we never over-award. Zero/negative
   * awards are a no-op; guest orders (no customerId) are filtered out before this is called.
   *
   * @param eventId the {@code OrderConfirmed} event id, the dedupe key for this accrual
   * @param tenantId owning tenant
   * @param customerId the customer to credit
   * @param orderId the order the points are earned against
   * @param total the order total the award is derived from
   * @param taxAmount the VAT inside that total, carried on so the ledger can defer the points'
   *     share of the sale's net revenue (17.11)
   */
  public void accrueLoyaltyFromOrder(
      UUID eventId,
      UUID tenantId,
      UUID customerId,
      UUID orderId,
      BigDecimal total,
      BigDecimal taxAmount) {
    BigDecimal points = total.multiply(pointsPerUnit()).setScale(2, RoundingMode.DOWN);
    if (points.signum() <= 0) {
      return; // nothing to award
    }
    var event =
        loyaltyEvent(
            LOYALTY_EARNED, TOPIC_EARNED, tenantId, customerId, points, orderId, total, taxAmount);
    repo.accrueFromOrderOnce(
        eventId,
        ORDER_CONFIRMED_CONSUMER,
        tenantId,
        customerId,
        orderId,
        points,
        "Loyalty for order " + orderId,
        event);
  }

  private static final String LOYALTY_EARNED = "LoyaltyEarned";
  private static final String TOPIC_EARNED = "storeql.customer.loyalty-earned";

  /**
   * A loyalty event. Each carries its own id, so the ledger that consumes them (purchase-svc,
   * 17.11) posts each once; an accrual carries the sale's total and the VAT inside it, from which
   * the points' share of the revenue is worked out.
   */
  private static OutboxRow loyaltyEvent(
      String eventType,
      String topic,
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      UUID orderId,
      BigDecimal orderTotal,
      BigDecimal orderTax) {
    var b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", eventType)
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("points", points);
    if (orderId != null) b.add("orderId", orderId.toString());
    if (orderTotal != null) {
      b.add("orderTotal", orderTotal)
          .add("orderTaxAmount", orderTax == null ? BigDecimal.ZERO : orderTax);
    }
    return new OutboxRow(eventType, topic, tenantId, customerId, b.build().toString());
  }

  private BigDecimal pointsPerUnit() {
    try {
      return new BigDecimal(pointsPerUnitRaw.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ONE; // misconfiguration falls back to 1 point per unit rather than failing
    }
  }

  /**
   * Spends loyalty points and publishes {@code LoyaltyRedeemed}.
   *
   * <p>The balance check happens in the repository, inside the same transaction as the ledger
   * write, so concurrent redemptions cannot together overdraw the account.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to debit
   * @param req the points, an optional order being paid towards and a reason for the ledger
   * @return the account with its new balance
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists; a conflict
   *     when the balance is insufficient
   */
  public LoyaltyAccount redeemPoints(UUID tenantId, UUID customerId, RedeemPointsRequest req) {
    get(tenantId, customerId);
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    var event =
        loyaltyEvent(
            "LoyaltyRedeemed",
            "storeql.customer.loyalty-redeemed",
            tenantId,
            customerId,
            req.points(),
            orderId,
            null,
            null);
    return repo.redeemPoints(tenantId, customerId, req.points(), orderId, req.reason(), event);
  }

  /**
   * Applies a manual correction to a points balance and publishes {@code LoyaltyAdjusted}.
   *
   * <p>Signed: a negative value removes points. This is the goodwill/correction path, distinct from
   * the earn and redeem ledgers.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose balance to correct
   * @param req the signed point delta and a reason for the ledger
   * @return the account with its new balance
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public LoyaltyAccount adjustPoints(UUID tenantId, UUID customerId, AdjustPointsRequest req) {
    get(tenantId, customerId);
    var event =
        loyaltyEvent(
            "LoyaltyAdjusted",
            "storeql.customer.loyalty-adjusted",
            tenantId,
            customerId,
            req.points(),
            null,
            null,
            null);
    return repo.adjustPoints(tenantId, customerId, req.points(), req.reason(), event);
  }

  /**
   * Reads the append-only loyalty ledger, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose ledger to read
   * @param limit page size; silently capped at 100
   * @return the ledger entries, newest first
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public List<LoyaltyLedgerEntry> getLedger(UUID tenantId, UUID customerId, int limit) {
    get(tenantId, customerId);
    return repo.listLedger(tenantId, customerId, Math.min(limit, 100));
  }

  /**
   * Reads the loyalty ledger for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose ledger to read
   * @param limit page size; silently capped at 100
   * @param ctx caller context; staff may read anyone in the tenant, a customer only themselves
   * @return the ledger entries, newest first
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists or the
   *     caller may not read it
   */
  public List<LoyaltyLedgerEntry> getLedger(
      UUID tenantId, UUID customerId, int limit, TenantContext ctx) {
    requireReadAccess(customerId, ctx);
    return getLedger(tenantId, customerId, limit);
  }

  // ── store credit ──────────────────────────────────────────────────────────

  /**
   * The currency a store-credit account is held in: the one named, upper-cased, or the tenant's own
   * (SJ-D53). Store credit is kept per currency, so reading, issuing and redeeming must agree on
   * which account a request means.
   *
   * @throws ApiException 400 {@code CURRENCY_INVALID} for a named code that is not one; 503 {@code
   *     TENANT_PROFILE_UNAVAILABLE} when none is named and the tenant's cannot be read
   */
  private String storeCreditCurrency(UUID tenantId, String requested) {
    return profiles.currencyOr(tenantId, requested);
  }

  /**
   * Reads a store-credit balance, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>Balances are per currency. A customer with no balance in that currency gets a synthesised
   * zero account rather than empty, and that stand-in is not persisted.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose balance to read
   * @param currency ISO-4217 code; blank or {@code null} means the tenant's own currency
   * @return the store-credit account, real or a zero-balance stand-in
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public StoreCreditAccount getStoreCredit(UUID tenantId, UUID customerId, String currency) {
    get(tenantId, customerId);
    String cur = storeCreditCurrency(tenantId, currency);
    return repo.findStoreCreditAccount(tenantId, customerId, cur)
        .orElseGet(
            () ->
                new StoreCreditAccount(
                    Ids.newId(),
                    tenantId,
                    customerId,
                    BigDecimal.ZERO,
                    cur,
                    Instant.now(),
                    Instant.now()));
  }

  /**
   * Reads a store-credit balance for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param customerId the customer whose balance to read
   * @param currency ISO-4217 code; blank or {@code null} means the tenant's own currency
   * @param ctx caller context; staff may read anyone in the tenant, a customer only themselves
   * @return the store-credit account, real or a zero-balance stand-in
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists or the
   *     caller may not read it
   */
  public StoreCreditAccount getStoreCredit(
      UUID tenantId, UUID customerId, String currency, TenantContext ctx) {
    requireReadAccess(customerId, ctx);
    return getStoreCredit(tenantId, customerId, currency);
  }

  /**
   * Issues store credit and publishes {@code StoreCreditIssued}.
   *
   * <p>Used for refunds-to-credit and goodwill. Not idempotent — calling it twice issues twice.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to credit
   * @param req the amount, optional currency (the tenant's own when omitted), originating order and
   *     reason
   * @return the account with its new balance
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists in this
   *     tenant
   */
  public StoreCreditAccount issueStoreCredit(
      UUID tenantId, UUID customerId, IssueStoreCreditRequest req) {
    get(tenantId, customerId);
    String cur = storeCreditCurrency(tenantId, req.currency());
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("amount", req.amount())
            .add("currency", cur)
            .build()
            .toString();
    var event =
        new OutboxRow(
            "StoreCreditIssued",
            "storeql.customer.store-credit-issued",
            tenantId,
            customerId,
            payload);
    return repo.issueStoreCredit(
        tenantId, customerId, req.amount(), cur, orderId, req.reason(), event);
  }

  /**
   * Spends store credit and publishes {@code StoreCreditRedeemed}.
   *
   * <p>Called by payment-svc before a {@code STORE_CREDIT} tender is recorded, so an insufficient
   * balance rejects the tender rather than inflating what the order counts as paid.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to debit
   * @param req the amount, optional currency (the tenant's own when omitted), order being paid and
   *     reason
   * @return the account with its new balance
   * @throws ApiException {@code CUSTOMER_NOT_FOUND} (404) when no such customer exists; a 422 when
   *     the balance is insufficient
   */
  public StoreCreditAccount redeemStoreCredit(
      UUID tenantId, UUID customerId, RedeemStoreCreditRequest req) {
    get(tenantId, customerId);
    String cur = storeCreditCurrency(tenantId, req.currency());
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("amount", req.amount())
            .add("currency", cur)
            .build()
            .toString();
    var event =
        new OutboxRow(
            "StoreCreditRedeemed",
            "storeql.customer.store-credit-redeemed",
            tenantId,
            customerId,
            payload);
    return repo.redeemStoreCredit(
        tenantId, customerId, req.amount(), cur, orderId, req.reason(), event);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static LocalDate parseDate(String s) {
    return s == null || s.isBlank() ? null : com.storeql.web.Parsing.date(s, "dob");
  }
}
