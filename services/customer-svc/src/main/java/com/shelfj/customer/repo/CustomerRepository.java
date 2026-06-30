package com.shelfj.customer.repo;

import com.shelfj.customer.domain.Domain.Customer;
import com.shelfj.customer.domain.Domain.CustomerAddress;
import com.shelfj.customer.domain.Domain.LoyaltyAccount;
import com.shelfj.customer.domain.Domain.LoyaltyLedgerEntry;
import com.shelfj.customer.domain.Domain.StoreCreditAccount;
import com.shelfj.customer.domain.Domain.StoreCreditLedgerEntry;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for customers, addresses, loyalty, and store credit. Every query on tenant-owned data
 * filters by tenant_id FIRST (golden rule #3). loyalty_ledger and store_credit_ledger are
 * append-only (golden rule #8).
 */
@ApplicationScoped
public class CustomerRepository extends BaseOutboxRepository {

  // ─────────────────────────────────────────── customers

  public Customer createCustomer(Customer c, OutboxRow event) {
    return inTx(
        conn -> {
          insertCustomer(conn, c);
          insertOutbox(conn, event);
          return c;
        },
        "create customer");
  }

  public Optional<Customer> findById(UUID tenantId, UUID customerId) {
    return query(
            "SELECT id, tenant_id, email, phone, first_name, last_name, dob, gender, status,"
                + " gdpr_consent_at, anonymized_at, created_at, updated_at"
                + " FROM customers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            CustomerRepository::mapCustomer,
            "find customer by id")
        .stream()
        .findFirst();
  }

  public Optional<Customer> findByEmail(UUID tenantId, String email) {
    return query(
            "SELECT id, tenant_id, email, phone, first_name, last_name, dob, gender, status,"
                + " gdpr_consent_at, anonymized_at, created_at, updated_at"
                + " FROM customers WHERE tenant_id = ? AND email = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, email);
            },
            CustomerRepository::mapCustomer,
            "find customer by email")
        .stream()
        .findFirst();
  }

  public Optional<Customer> findByPhone(UUID tenantId, String phone) {
    return query(
            "SELECT id, tenant_id, email, phone, first_name, last_name, dob, gender, status,"
                + " gdpr_consent_at, anonymized_at, created_at, updated_at"
                + " FROM customers WHERE tenant_id = ? AND phone = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, phone);
            },
            CustomerRepository::mapCustomer,
            "find customer by phone")
        .stream()
        .findFirst();
  }

  /** Cursor-based page — returns up to limit+1 rows so caller can detect next page. */
  public List<Customer> listCustomers(UUID tenantId, String afterId, int limit) {
    if (afterId == null) {
      return query(
          "SELECT id, tenant_id, email, phone, first_name, last_name, dob, gender, status,"
              + " gdpr_consent_at, anonymized_at, created_at, updated_at"
              + " FROM customers WHERE tenant_id = ? AND status != 'ANONYMIZED'"
              + " ORDER BY created_at DESC, id LIMIT ?",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit + 1);
          },
          CustomerRepository::mapCustomer,
          "list customers");
    }
    return query(
        "SELECT id, tenant_id, email, phone, first_name, last_name, dob, gender, status,"
            + " gdpr_consent_at, anonymized_at, created_at, updated_at"
            + " FROM customers WHERE tenant_id = ? AND status != 'ANONYMIZED'"
            + " AND id < ? ORDER BY created_at DESC, id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, UUID.fromString(afterId));
          ps.setInt(3, limit + 1);
        },
        CustomerRepository::mapCustomer,
        "list customers paged");
  }

  public Customer updateCustomer(Customer c) {
    exec(
        "UPDATE customers SET phone=?, first_name=?, last_name=?, dob=?, gender=?,"
            + " gdpr_consent_at=?, updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, c.phone());
          ps.setString(2, c.firstName());
          ps.setString(3, c.lastName());
          ps.setObject(4, c.dob() == null ? null : java.sql.Date.valueOf(c.dob()));
          ps.setString(5, c.gender());
          ps.setObject(
              6, c.gdprConsentAt() == null ? null : c.gdprConsentAt().atOffset(ZoneOffset.UTC));
          ps.setObject(7, c.updatedAt().atOffset(ZoneOffset.UTC));
          ps.setObject(8, c.tenantId());
          ps.setObject(9, c.id());
        },
        "update customer");
    return findById(c.tenantId(), c.id())
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  public Customer anonymize(UUID tenantId, UUID customerId) {
    Instant now = Instant.now();
    exec(
        "UPDATE customers SET email = 'anon-' || id || '@deleted', phone = NULL,"
            + " first_name = 'Deleted', last_name = 'User', dob = NULL, gender = NULL,"
            + " gdpr_consent_at = NULL, status = 'ANONYMIZED', anonymized_at = ?,"
            + " updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, customerId);
        },
        "anonymize customer");
    return findById(tenantId, customerId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  // ─────────────────────────────────────────── addresses

  public CustomerAddress createAddress(CustomerAddress a) {
    return inTx(
        conn -> {
          if (a.isDefault()) {
            clearDefaultAddresses(conn, a.tenantId(), a.customerId());
          }
          insertAddress(conn, a);
          return a;
        },
        "create address");
  }

  public List<CustomerAddress> listAddresses(UUID tenantId, UUID customerId) {
    return query(
        "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
            + " pincode, is_default, created_at"
            + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        CustomerRepository::mapAddress,
        "list addresses");
  }

  public CustomerAddress updateAddress(CustomerAddress a) {
    return inTx(
        conn -> {
          if (a.isDefault()) {
            clearDefaultAddresses(conn, a.tenantId(), a.customerId());
          }
          exec(
              conn,
              "UPDATE customer_addresses SET type=?, line1=?, line2=?, city=?, state=?,"
                  + " country=?, pincode=?, is_default=? WHERE tenant_id=? AND customer_id=? AND"
                  + " id=?",
              ps -> {
                ps.setString(1, a.type());
                ps.setString(2, a.line1());
                ps.setString(3, a.line2());
                ps.setString(4, a.city());
                ps.setString(5, a.state());
                ps.setString(6, a.country());
                ps.setString(7, a.pincode());
                ps.setBoolean(8, a.isDefault());
                ps.setObject(9, a.tenantId());
                ps.setObject(10, a.customerId());
                ps.setObject(11, a.id());
              });
          return findAddress(conn, a.tenantId(), a.customerId(), a.id());
        },
        "update address tx");
  }

  public void deleteAddress(UUID tenantId, UUID customerId, UUID addressId) {
    exec(
        "DELETE FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setObject(3, addressId);
        },
        "delete address");
  }

  public Optional<CustomerAddress> findAddress(UUID tenantId, UUID customerId, UUID addressId) {
    return query(
            "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
                + " pincode, is_default, created_at"
                + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setObject(3, addressId);
            },
            CustomerRepository::mapAddress,
            "find address")
        .stream()
        .findFirst();
  }

  // ─────────────────────────────────────────── loyalty

  public LoyaltyAccount earnPoints(
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          BigDecimal newBalance = account.pointsBalance().add(points);
          BigDecimal newLifetime = account.lifetimePoints().add(points);
          String newTier = LoyaltyAccount.tierFor(newLifetime);
          LoyaltyAccount updated =
              updateLoyaltyAccount(conn, tenantId, customerId, newBalance, newLifetime, newTier);
          UUID entryId = UUID.randomUUID();
          insertLedgerEntry(
              conn,
              new LoyaltyLedgerEntry(
                  entryId,
                  tenantId,
                  customerId,
                  LoyaltyLedgerEntry.TYPE_EARN,
                  points,
                  newBalance,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "earn loyalty points");
  }

  public LoyaltyAccount redeemPoints(
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          if (account.pointsBalance().compareTo(points) < 0) {
            throw new ApiException(
                422,
                "LOYALTY_INSUFFICIENT_POINTS",
                "Insufficient loyalty points",
                java.util.List.of());
          }
          BigDecimal newBalance = account.pointsBalance().subtract(points);
          LoyaltyAccount updated =
              updateLoyaltyAccount(
                  conn, tenantId, customerId, newBalance, account.lifetimePoints(), account.tier());
          insertLedgerEntry(
              conn,
              new LoyaltyLedgerEntry(
                  UUID.randomUUID(),
                  tenantId,
                  customerId,
                  LoyaltyLedgerEntry.TYPE_REDEEM,
                  points.negate(),
                  newBalance,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "redeem loyalty points");
  }

  public LoyaltyAccount adjustPoints(
      UUID tenantId, UUID customerId, BigDecimal points, String reason, OutboxRow event) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          BigDecimal newBalance = account.pointsBalance().add(points).max(BigDecimal.ZERO);
          BigDecimal newLifetime =
              points.compareTo(BigDecimal.ZERO) > 0
                  ? account.lifetimePoints().add(points)
                  : account.lifetimePoints();
          String newTier = LoyaltyAccount.tierFor(newLifetime);
          LoyaltyAccount updated =
              updateLoyaltyAccount(conn, tenantId, customerId, newBalance, newLifetime, newTier);
          insertLedgerEntry(
              conn,
              new LoyaltyLedgerEntry(
                  UUID.randomUUID(),
                  tenantId,
                  customerId,
                  LoyaltyLedgerEntry.TYPE_ADJUST,
                  points,
                  newBalance,
                  null,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "adjust loyalty points");
  }

  public Optional<LoyaltyAccount> findLoyaltyAccount(UUID tenantId, UUID customerId) {
    return query(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            CustomerRepository::mapLoyaltyAccount,
            "find loyalty account")
        .stream()
        .findFirst();
  }

  public List<LoyaltyLedgerEntry> listLedger(UUID tenantId, UUID customerId, int limit) {
    return query(
        "SELECT id, tenant_id, customer_id, type, points, balance_after, order_id,"
            + " reason, created_at"
            + " FROM loyalty_ledger WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setInt(3, limit);
        },
        CustomerRepository::mapLedgerEntry,
        "list loyalty ledger");
  }

  // ─────────────────────────────────────────── store credit

  public StoreCreditAccount issueStoreCredit(
      UUID tenantId,
      UUID customerId,
      BigDecimal amount,
      String currency,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          StoreCreditAccount account =
              getOrCreateStoreCreditAccount(conn, tenantId, customerId, currency);
          BigDecimal newBalance = account.balance().add(amount);
          StoreCreditAccount updated =
              updateStoreCreditAccount(conn, tenantId, customerId, currency, newBalance);
          insertStoreCreditEntry(
              conn,
              new StoreCreditLedgerEntry(
                  UUID.randomUUID(),
                  tenantId,
                  customerId,
                  StoreCreditLedgerEntry.TYPE_ISSUE,
                  amount,
                  newBalance,
                  currency,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "issue store credit");
  }

  public StoreCreditAccount redeemStoreCredit(
      UUID tenantId,
      UUID customerId,
      BigDecimal amount,
      String currency,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          StoreCreditAccount account =
              getOrCreateStoreCreditAccount(conn, tenantId, customerId, currency);
          if (account.balance().compareTo(amount) < 0) {
            throw new ApiException(
                422, "STORE_CREDIT_INSUFFICIENT", "Insufficient store credit", java.util.List.of());
          }
          BigDecimal newBalance = account.balance().subtract(amount);
          StoreCreditAccount updated =
              updateStoreCreditAccount(conn, tenantId, customerId, currency, newBalance);
          insertStoreCreditEntry(
              conn,
              new StoreCreditLedgerEntry(
                  UUID.randomUUID(),
                  tenantId,
                  customerId,
                  StoreCreditLedgerEntry.TYPE_REDEEM,
                  amount.negate(),
                  newBalance,
                  currency,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "redeem store credit");
  }

  public Optional<StoreCreditAccount> findStoreCreditAccount(
      UUID tenantId, UUID customerId, String currency) {
    return query(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts WHERE tenant_id = ? AND customer_id = ? AND currency = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setString(3, currency);
            },
            CustomerRepository::mapStoreCreditAccount,
            "find store credit account")
        .stream()
        .findFirst();
  }

  // ─────────────────────────────────────────── private helpers

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new ApiException(
          409,
          "CUSTOMER_ALREADY_EXISTS",
          "A customer with this email already exists",
          java.util.List.of(),
          e);
    }
    return dbError(what, e);
  }

  private void insertCustomer(Connection c, Customer customer) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO customers (id, tenant_id, email, phone, first_name, last_name,"
                + " dob, gender, status, gdpr_consent_at, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, customer.id());
      ps.setObject(2, customer.tenantId());
      ps.setString(3, customer.email());
      ps.setString(4, customer.phone());
      ps.setString(5, customer.firstName());
      ps.setString(6, customer.lastName());
      ps.setObject(7, customer.dob() == null ? null : java.sql.Date.valueOf(customer.dob()));
      ps.setString(8, customer.gender());
      ps.setString(9, customer.status());
      ps.setObject(
          10,
          customer.gdprConsentAt() == null
              ? null
              : customer.gdprConsentAt().atOffset(ZoneOffset.UTC));
      ps.setObject(11, customer.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(12, customer.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void clearDefaultAddresses(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE customer_addresses SET is_default = false WHERE tenant_id = ? AND customer_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.executeUpdate();
    }
  }

  private void insertAddress(Connection c, CustomerAddress a) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO customer_addresses (id, tenant_id, customer_id, type, line1, line2,"
                + " city, state, country, pincode, is_default, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, a.id());
      ps.setObject(2, a.tenantId());
      ps.setObject(3, a.customerId());
      ps.setString(4, a.type());
      ps.setString(5, a.line1());
      ps.setString(6, a.line2());
      ps.setString(7, a.city());
      ps.setString(8, a.state());
      ps.setString(9, a.country());
      ps.setString(10, a.pincode());
      ps.setBoolean(11, a.isDefault());
      ps.setObject(12, a.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private CustomerAddress findAddress(Connection c, UUID tenantId, UUID customerId, UUID addressId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
                + " pincode, is_default, created_at"
                + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, addressId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapAddress(rs);
        throw ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found");
      }
    }
  }

  private void exec(Connection c, String sql, ThrowingConsumer<PreparedStatement> binder)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      binder.accept(ps);
      ps.executeUpdate();
    }
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T t) throws SQLException;
  }

  private LoyaltyAccount getOrCreateLoyaltyAccount(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO loyalty_accounts (id, tenant_id, customer_id, points_balance,"
                + " lifetime_points, tier, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, customer_id) DO NOTHING")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setObject(3, customerId);
      ps.setBigDecimal(4, BigDecimal.ZERO);
      ps.setBigDecimal(5, BigDecimal.ZERO);
      ps.setString(6, LoyaltyAccount.TIER_BRONZE);
      ps.setObject(7, now.atOffset(ZoneOffset.UTC));
      ps.setObject(8, now.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    // FOR UPDATE: this row is read-modify-written by earn/redeem/adjust. Locking it for the
    // duration of the transaction serializes concurrent point mutations on the same account, so two
    // simultaneous redeems can't both pass the balance check and double-spend (golden rule:
    // money/balance mutations take a row lock — same pattern as gift-card/inventory/payment).
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapLoyaltyAccount(rs);
      }
    }
    throw new IllegalStateException(
        "loyalty account missing after upsert for customer " + customerId);
  }

  private LoyaltyAccount updateLoyaltyAccount(
      Connection c,
      UUID tenantId,
      UUID customerId,
      BigDecimal balance,
      BigDecimal lifetime,
      String tier)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE loyalty_accounts SET points_balance=?, lifetime_points=?, tier=?, updated_at=?"
                + " WHERE tenant_id=? AND customer_id=?")) {
      ps.setBigDecimal(1, balance);
      ps.setBigDecimal(2, lifetime);
      ps.setString(3, tier);
      ps.setObject(4, now.atOffset(ZoneOffset.UTC));
      ps.setObject(5, tenantId);
      ps.setObject(6, customerId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("loyalty account disappeared after update");
        }
        return mapLoyaltyAccount(rs);
      }
    }
  }

  private void insertLedgerEntry(Connection c, LoyaltyLedgerEntry e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO loyalty_ledger (id, tenant_id, customer_id, type, points, balance_after,"
                + " order_id, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.type());
      ps.setBigDecimal(5, e.points());
      ps.setBigDecimal(6, e.balanceAfter());
      ps.setObject(7, e.orderId());
      ps.setString(8, e.reason());
      ps.setObject(9, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private StoreCreditAccount getOrCreateStoreCreditAccount(
      Connection c, UUID tenantId, UUID customerId, String currency) throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO store_credit_accounts (id, tenant_id, customer_id, balance, currency,"
                + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, customer_id, currency) DO NOTHING")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setObject(3, customerId);
      ps.setBigDecimal(4, BigDecimal.ZERO);
      ps.setString(5, currency);
      ps.setObject(6, now.atOffset(ZoneOffset.UTC));
      ps.setObject(7, now.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    // FOR UPDATE: store credit is real money. Lock the account row so concurrent issue/redeem on
    // the same account serialize — otherwise two simultaneous redeems both read the same balance,
    // both pass the guard, and both write, over-spending the balance (there is no DB CHECK
    // backstop).
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts"
                + " WHERE tenant_id = ? AND customer_id = ? AND currency = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setString(3, currency);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapStoreCreditAccount(rs);
      }
    }
    throw new IllegalStateException(
        "store_credit_account missing after upsert for customer " + customerId);
  }

  private StoreCreditAccount updateStoreCreditAccount(
      Connection c, UUID tenantId, UUID customerId, String currency, BigDecimal balance)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE store_credit_accounts SET balance=?, updated_at=?"
                + " WHERE tenant_id=? AND customer_id=? AND currency=?")) {
      ps.setBigDecimal(1, balance);
      ps.setObject(2, now.atOffset(ZoneOffset.UTC));
      ps.setObject(3, tenantId);
      ps.setObject(4, customerId);
      ps.setString(5, currency);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts WHERE tenant_id=? AND customer_id=? AND currency=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setString(3, currency);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("store credit account disappeared after update");
        }
        return mapStoreCreditAccount(rs);
      }
    }
  }

  private void insertStoreCreditEntry(Connection c, StoreCreditLedgerEntry e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO store_credit_ledger (id, tenant_id, customer_id, type, amount, balance_after,"
                + " currency, order_id, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.type());
      ps.setBigDecimal(5, e.amount());
      ps.setBigDecimal(6, e.balanceAfter());
      ps.setString(7, e.currency());
      ps.setObject(8, e.orderId());
      ps.setString(9, e.reason());
      ps.setObject(10, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  // ─────────────────────────────────────────── row mappers

  private static Customer mapCustomer(ResultSet rs) throws SQLException {
    OffsetDateTime gdpr = rs.getObject("gdpr_consent_at", OffsetDateTime.class);
    OffsetDateTime anon = rs.getObject("anonymized_at", OffsetDateTime.class);
    LocalDate dob = rs.getObject("dob", LocalDate.class);
    return new Customer(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("first_name"),
        rs.getString("last_name"),
        dob,
        rs.getString("gender"),
        rs.getString("status"),
        gdpr == null ? null : gdpr.toInstant(),
        anon == null ? null : anon.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CustomerAddress mapAddress(ResultSet rs) throws SQLException {
    return new CustomerAddress(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("type"),
        rs.getString("line1"),
        rs.getString("line2"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("country"),
        rs.getString("pincode"),
        rs.getBoolean("is_default"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static LoyaltyAccount mapLoyaltyAccount(ResultSet rs) throws SQLException {
    return new LoyaltyAccount(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getBigDecimal("points_balance"),
        rs.getBigDecimal("lifetime_points"),
        rs.getString("tier"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static LoyaltyLedgerEntry mapLedgerEntry(ResultSet rs) throws SQLException {
    UUID orderId = rs.getObject("order_id", UUID.class);
    return new LoyaltyLedgerEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("type"),
        rs.getBigDecimal("points"),
        rs.getBigDecimal("balance_after"),
        orderId,
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static StoreCreditAccount mapStoreCreditAccount(ResultSet rs) throws SQLException {
    return new StoreCreditAccount(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getBigDecimal("balance"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
