package com.shelfj.customer.service;

import com.shelfj.customer.domain.Domain.Customer;
import com.shelfj.customer.domain.Domain.CustomerAddress;
import com.shelfj.customer.domain.Domain.LoyaltyAccount;
import com.shelfj.customer.domain.Domain.LoyaltyLedgerEntry;
import com.shelfj.customer.domain.Domain.StoreCreditAccount;
import com.shelfj.customer.dto.Dtos.AddAddressRequest;
import com.shelfj.customer.dto.Dtos.AdjustPointsRequest;
import com.shelfj.customer.dto.Dtos.EarnPointsRequest;
import com.shelfj.customer.dto.Dtos.IssueStoreCreditRequest;
import com.shelfj.customer.dto.Dtos.RedeemPointsRequest;
import com.shelfj.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.shelfj.customer.dto.Dtos.RegisterCustomerRequest;
import com.shelfj.customer.dto.Dtos.UpdateCustomerRequest;
import com.shelfj.customer.repo.CustomerRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
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

  /** Points awarded per unit of order currency spent (e.g. 1 → 1 point per £1). */
  @Inject
  @ConfigProperty(name = "shelfj.customer.loyalty.points-per-unit", defaultValue = "1")
  String pointsPerUnitRaw;

  // ── customer profile ──────────────────────────────────────────────────────

  public Customer register(UUID tenantId, RegisterCustomerRequest req) {
    if (repo.findByEmail(tenantId, req.email()).isPresent()) {
      throw new ApiException(
          409, "CUSTOMER_ALREADY_EXISTS", "A customer with this email already exists", List.of());
    }
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    var customer =
        new Customer(
            id,
            tenantId,
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
            "CustomerRegistered", "shelfj.customer.customer-registered", tenantId, id, payload);
    return repo.createCustomer(customer, event);
  }

  public Customer get(UUID tenantId, UUID customerId) {
    return repo.findById(tenantId, customerId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  public List<Customer> list(UUID tenantId, String afterId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listCustomers(tenantId, afterId, cap);
  }

  public Customer update(UUID tenantId, UUID customerId, UpdateCustomerRequest req) {
    Customer existing = get(tenantId, customerId);
    Instant now = Instant.now();
    Instant gdprConsent = Boolean.TRUE.equals(req.gdprConsent()) ? now : existing.gdprConsentAt();
    var updated =
        new Customer(
            existing.id(),
            tenantId,
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

  /** GDPR right-to-erasure: anonymizes PII in place; retains the record for audit. */
  public Customer anonymize(UUID tenantId, UUID customerId) {
    get(tenantId, customerId);
    return repo.anonymize(tenantId, customerId);
  }

  /** POS barcode / QR code lookup by email or phone. */
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

  public CustomerAddress addAddress(UUID tenantId, UUID customerId, AddAddressRequest req) {
    get(tenantId, customerId);
    String type =
        req.type() == null || req.type().isBlank() ? CustomerAddress.TYPE_HOME : req.type();
    var address =
        new CustomerAddress(
            UUID.randomUUID(),
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

  public List<CustomerAddress> listAddresses(UUID tenantId, UUID customerId) {
    get(tenantId, customerId);
    return repo.listAddresses(tenantId, customerId);
  }

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

  public void deleteAddress(UUID tenantId, UUID customerId, UUID addressId) {
    repo.findAddress(tenantId, customerId, addressId)
        .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found"));
    repo.deleteAddress(tenantId, customerId, addressId);
  }

  // ── loyalty ───────────────────────────────────────────────────────────────

  public LoyaltyAccount getLoyaltyAccount(UUID tenantId, UUID customerId) {
    get(tenantId, customerId);
    return repo.findLoyaltyAccount(tenantId, customerId)
        .orElseGet(
            () ->
                new LoyaltyAccount(
                    UUID.randomUUID(),
                    tenantId,
                    customerId,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LoyaltyAccount.TIER_BRONZE,
                    Instant.now(),
                    Instant.now()));
  }

  public LoyaltyAccount earnPoints(UUID tenantId, UUID customerId, EarnPointsRequest req) {
    get(tenantId, customerId);
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("points", req.points())
            .build()
            .toString();
    var event =
        new OutboxRow(
            "LoyaltyEarned", "shelfj.customer.loyalty-earned", tenantId, customerId, payload);
    return repo.earnPoints(tenantId, customerId, req.points(), orderId, req.reason(), event);
  }

  /**
   * Accrue loyalty points for a confirmed order, driven by the {@code OrderConfirmed} event and
   * idempotent on its {@code eventId}. Points = order total × {@code
   * shelfj.customer.loyalty.points-per-unit}, rounded down so we never over-award. Zero/negative
   * awards are a no-op; guest orders (no customerId) are filtered out before this is called.
   */
  public void accrueLoyaltyFromOrder(
      UUID eventId, UUID tenantId, UUID customerId, UUID orderId, BigDecimal total) {
    BigDecimal points = total.multiply(pointsPerUnit()).setScale(2, RoundingMode.DOWN);
    if (points.signum() <= 0) {
      return; // nothing to award
    }
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("orderId", orderId.toString())
            .add("points", points)
            .build()
            .toString();
    var event =
        new OutboxRow(
            "LoyaltyEarned", "shelfj.customer.loyalty-earned", tenantId, customerId, payload);
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

  private BigDecimal pointsPerUnit() {
    try {
      return new BigDecimal(pointsPerUnitRaw.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ONE; // misconfiguration falls back to 1 point per unit rather than failing
    }
  }

  public LoyaltyAccount redeemPoints(UUID tenantId, UUID customerId, RedeemPointsRequest req) {
    get(tenantId, customerId);
    UUID orderId = req.orderId() == null ? null : UUID.fromString(req.orderId());
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("points", req.points())
            .build()
            .toString();
    var event =
        new OutboxRow(
            "LoyaltyRedeemed", "shelfj.customer.loyalty-redeemed", tenantId, customerId, payload);
    return repo.redeemPoints(tenantId, customerId, req.points(), orderId, req.reason(), event);
  }

  public LoyaltyAccount adjustPoints(UUID tenantId, UUID customerId, AdjustPointsRequest req) {
    get(tenantId, customerId);
    String payload =
        Json.createObjectBuilder()
            .add("customerId", customerId.toString())
            .add("tenantId", tenantId.toString())
            .add("points", req.points())
            .build()
            .toString();
    var event =
        new OutboxRow(
            "LoyaltyAdjusted", "shelfj.customer.loyalty-adjusted", tenantId, customerId, payload);
    return repo.adjustPoints(tenantId, customerId, req.points(), req.reason(), event);
  }

  public List<LoyaltyLedgerEntry> getLedger(UUID tenantId, UUID customerId, int limit) {
    get(tenantId, customerId);
    return repo.listLedger(tenantId, customerId, Math.min(limit, 100));
  }

  // ── store credit ──────────────────────────────────────────────────────────

  public StoreCreditAccount getStoreCredit(UUID tenantId, UUID customerId, String currency) {
    get(tenantId, customerId);
    String cur = currency == null || currency.isBlank() ? "GBP" : currency.toUpperCase(Locale.ROOT);
    return repo.findStoreCreditAccount(tenantId, customerId, cur)
        .orElseGet(
            () ->
                new StoreCreditAccount(
                    UUID.randomUUID(),
                    tenantId,
                    customerId,
                    BigDecimal.ZERO,
                    cur,
                    Instant.now(),
                    Instant.now()));
  }

  public StoreCreditAccount issueStoreCredit(
      UUID tenantId, UUID customerId, IssueStoreCreditRequest req) {
    get(tenantId, customerId);
    String cur =
        req.currency() == null || req.currency().isBlank()
            ? "GBP"
            : req.currency().toUpperCase(Locale.ROOT);
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
            "shelfj.customer.store-credit-issued",
            tenantId,
            customerId,
            payload);
    return repo.issueStoreCredit(
        tenantId, customerId, req.amount(), cur, orderId, req.reason(), event);
  }

  public StoreCreditAccount redeemStoreCredit(
      UUID tenantId, UUID customerId, RedeemStoreCreditRequest req) {
    get(tenantId, customerId);
    String cur =
        req.currency() == null || req.currency().isBlank()
            ? "GBP"
            : req.currency().toUpperCase(Locale.ROOT);
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
            "shelfj.customer.store-credit-redeemed",
            tenantId,
            customerId,
            payload);
    return repo.redeemStoreCredit(
        tenantId, customerId, req.amount(), cur, orderId, req.reason(), event);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static LocalDate parseDate(String s) {
    return s == null || s.isBlank() ? null : com.shelfj.web.Parsing.date(s, "dob");
  }
}
