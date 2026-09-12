package com.shelfj.order.repo;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.Domain.AgeVerification;
import com.shelfj.order.domain.Domain.AgeVerificationSummary;
import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderDiscount;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.OrderReceipt;
import com.shelfj.order.domain.Domain.OrderStatusHistory;
import com.shelfj.order.domain.Domain.PosLogEntry;
import com.shelfj.order.domain.Domain.PosVoidLog;
import com.shelfj.order.domain.Domain.Return;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.order.domain.Domain.SpecialOrder;
import com.shelfj.order.domain.Domain.SpecialOrderItem;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Order persistence (JDBC). Every query filters tenant_id first. */
@ApplicationScoped
public class OrderRepository extends BaseOutboxRepository {

  /** processed_events key for the PaymentRefunded → order status/accumulation path (dedupe). */
  private static final String REFUND_CONSUMER = "order-svc/payment-refunded";

  // ── Orders ────────────────────────────────────────────────────────────────

  /**
   * Persists an order, its items, its status history and its outbox event; when {@code discount} is
   * non-null, the discount audit row commits in the same transaction, so an order can never carry a
   * discount that no record explains (SJ-D6).
   */
  public Order createOrder(
      Order order, List<OrderItem> items, OutboxRow event, OrderDiscount discount) {
    return createOrder(order, items, event, discount, List.of());
  }

  /**
   * @param appliedPromotions what the promotion engine took off, written in the same transaction as
   *     the order so a receipt can never print a discount the order does not carry
   */
  public Order createOrder(
      Order order,
      List<OrderItem> items,
      OutboxRow event,
      OrderDiscount discount,
      List<com.shelfj.order.client.PricingClient.AppliedPromotion> appliedPromotions) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO orders"
                      + " (id,tenant_id,store_id,customer_id,login_id,channel,fulfilment_type,"
                      + "  status,"
                      + "  subtotal,tax_amount,discount_amount,total,currency,notes,idempotency_key,"
                      + "  tax_exempt,exempt_reason,delivery_line1,delivery_line2,delivery_city,"
                      + "  delivery_postal_code,delivery_recipient_name,delivery_recipient_phone,contact_phone,"
                      + "  payment_method,promotion_discount)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, order.id());
            ps.setObject(2, order.tenantId());
            ps.setObject(3, order.storeId());
            ps.setObject(4, order.customerId());
            ps.setObject(5, order.loginId());
            ps.setString(6, order.channel());
            ps.setString(7, order.fulfilmentType());
            ps.setString(8, order.status());
            ps.setBigDecimal(9, order.subtotal());
            ps.setBigDecimal(10, order.taxAmount());
            ps.setBigDecimal(11, order.discountAmount());
            ps.setBigDecimal(12, order.total());
            ps.setString(13, order.currency());
            ps.setString(14, order.notes());
            ps.setString(15, order.idempotencyKey());
            ps.setBoolean(16, order.taxExempt());
            ps.setString(17, order.exemptReason());
            ps.setString(18, order.deliveryLine1());
            ps.setString(19, order.deliveryLine2());
            ps.setString(20, order.deliveryCity());
            ps.setString(21, order.deliveryPostalCode());
            ps.setString(22, order.deliveryRecipientName());
            ps.setString(23, order.deliveryRecipientPhone());
            ps.setString(24, order.contactPhone());
            ps.setString(25, order.paymentMethod());
            ps.setBigDecimal(
                26,
                order.promotionDiscount() == null
                    ? java.math.BigDecimal.ZERO
                    : order.promotionDiscount());
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "ORDER_DUPLICATE_KEY",
                  "duplicate idempotency key",
                  java.util.List.of(),
                  sqle);
            throw sqle;
          }
          for (OrderItem item : items) insertOrderItem(c, item);
          appendStatusHistory(
              c, order.tenantId(), order.id(), null, order.status(), "created", null);
          if (discount != null) insertOrderDiscount(c, discount);
          insertOrderPromotionsTx(c, order.tenantId(), order.id(), appliedPromotions);
          insertOutbox(c, event);
          return order;
        },
        "create order");
  }

  /** Append-only (golden rule #8): inserted with the order, never updated or deleted. */
  private static void insertOrderDiscount(java.sql.Connection c, OrderDiscount d)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO order_discounts"
                + " (id,tenant_id,order_id,store_id,subtotal,discount_amount,discount_pct,"
                + "  reason,granted_by,granted_role)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.orderId());
      ps.setObject(4, d.storeId());
      ps.setBigDecimal(5, d.subtotal());
      ps.setBigDecimal(6, d.discountAmount());
      ps.setBigDecimal(7, d.discountPct());
      ps.setString(8, d.reason());
      ps.setObject(9, d.grantedBy());
      ps.setString(10, d.grantedRole());
      ps.executeUpdate();
    }
  }

  /** Look up an order by its idempotency key — used to replay a retried checkout. */
  public Optional<Order> findOrderByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return query(
            "SELECT id, tenant_id, store_id, customer_id, login_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, promotion_discount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason,"
                + " delivery_line1, delivery_line2, delivery_city, delivery_postal_code,"
                + " delivery_recipient_name, delivery_recipient_phone, contact_phone, payment_method"
                + " FROM orders WHERE tenant_id=? AND idempotency_key=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            rs -> mapOrder(rs),
            "find order by idempotency key")
        .stream()
        .findFirst();
  }

  /**
   * Keyset page of orders matching the given filters, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId restrict to one store, or {@code null}
   * @param customerId restrict to one customer, or {@code null}
   * @param loginId restrict to the orders one login placed, or {@code null}. Separate from {@code
   *     customerId} because they are different ids: this is what a shopper's own order history
   *     filters on, and it matches orders placed before the customer link existed (SJ-D44)
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null}
   * @param status restrict to one status, or {@code null}
   * @param from inclusive lower bound on creation time, or {@code null}
   * @param to exclusive upper bound on creation time, or {@code null}
   * @param afterCreatedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size to detect a next page
   * @return the page of orders
   */
  public List<Order> listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String status,
      Instant from,
      Instant to,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, customer_id, login_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, promotion_discount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason,"
                + " delivery_line1, delivery_line2, delivery_city, delivery_postal_code,"
                + " delivery_recipient_name, delivery_recipient_phone, contact_phone, payment_method"
                + " FROM orders WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (customerId != null) sql.append(" AND customer_id=?");
    if (loginId != null) sql.append(" AND login_id=?");
    if (channel != null) sql.append(" AND channel=?");
    if (status != null) sql.append(" AND status=?");
    if (from != null) sql.append(" AND created_at >= ?");
    if (to != null) sql.append(" AND created_at <= ?");
    // Keyset pagination: rows strictly after the cursor in (created_at DESC, id DESC) order.
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) < (?, ?)");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (customerId != null) ps.setObject(i++, customerId);
          if (loginId != null) ps.setObject(i++, loginId);
          if (channel != null) ps.setString(i++, channel.toUpperCase(java.util.Locale.ROOT));
          if (status != null) ps.setString(i++, status.toUpperCase(java.util.Locale.ROOT));
          if (from != null) ps.setObject(i++, from.atOffset(java.time.ZoneOffset.UTC));
          if (to != null) ps.setObject(i++, to.atOffset(java.time.ZoneOffset.UTC));
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        rs -> mapOrder(rs),
        "list orders");
  }

  /**
   * Looks an order up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order to fetch
   * @return the order, or empty when it does not exist in this tenant
   */
  public Optional<Order> findOrder(UUID tenantId, UUID orderId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, customer_id, login_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, promotion_discount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason,"
                + " delivery_line1, delivery_line2, delivery_city, delivery_postal_code,"
                + " delivery_recipient_name, delivery_recipient_phone, contact_phone, payment_method"
                + " FROM orders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            rs -> mapOrder(rs),
            "find order");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Moves an order between statuses, appends the history row and writes the event — atomically.
   *
   * <p>The update is conditional on {@code fromStatus}, which is what makes the transition safe
   * under concurrency: two callers racing to confirm or cancel the same order cannot both win, and
   * the loser fails rather than overwriting.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order to move
   * @param fromStatus the status the order must currently be in
   * @param toStatus the status to move it to
   * @param reason free-text reason recorded on the history row
   * @param userId the actor recorded on the history row
   * @param event the outbox row to commit alongside the transition
   * @return the order in its new status
   * @throws com.shelfj.web.ApiException a conflict when the order is no longer in {@code
   *     fromStatus}
   */
  public Order transitionOrderStatus(
      UUID tenantId,
      UUID orderId,
      String expectedStatus,
      String newStatus,
      String reason,
      UUID changedBy,
      OutboxRow event) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status=?")) {
            ps.setString(1, newStatus);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.setString(4, expectedStatus);
            rows = ps.executeUpdate();
          }
          if (rows == 0)
            throw ApiException.notFound(
                "ORDER_NOT_FOUND_OR_WRONG_STATUS",
                "order not found or not in status " + expectedStatus);
          appendStatusHistory(c, tenantId, orderId, expectedStatus, newStatus, reason, changedBy);
          if (event != null) insertOutbox(c, event);
          return findOrderInTx(c, tenantId, orderId);
        },
        "transition order " + orderId);
  }

  // ── SJ-D43: erasing a customer from this shop's orders ─────────────────────

  /**
   * States in which a sale is over, so nothing still has to reach the customer. An order in any
   * other state keeps its delivery details until it gets here.
   */
  private static final String SETTLED_ORDER =
      "('FULFILLED','CANCELLED','VOIDED','REFUNDED','PARTIALLY_REFUNDED')";

  private static final String REDACT_ORDER =
      " SET contact_phone = NULL, delivery_line1 = NULL, delivery_line2 = NULL,"
          + " delivery_city = NULL, delivery_postal_code = NULL, delivery_recipient_name = NULL,"
          + " delivery_recipient_phone = NULL, notes = NULL, updated_at = now()";

  private static final String REDACT_SPECIAL_ORDER =
      "UPDATE special_orders s SET customer_name = NULL, customer_phone = NULL,"
          + " customer_email = NULL, delivery_address = NULL, notes = NULL, updated_at = now()";

  private static final String SPECIAL_ORDER_SETTLED_AND_IDENTIFIES =
      " s.status IN ('FULFILLED','CANCELLED')"
          + " AND (s.customer_name IS NOT NULL OR s.customer_phone IS NOT NULL"
          + " OR s.customer_email IS NOT NULL OR s.delivery_address IS NOT NULL"
          + " OR s.notes IS NOT NULL)";

  /** A layaway's free-text notes are the only thing on it that can name the customer. */
  private static final String LAYAWAY_SETTLED_WITH_NOTES =
      " l.status IN ('COMPLETED','CANCELLED') AND l.notes IS NOT NULL";

  private static final String ORDER_STILL_IDENTIFIES =
      " (o.contact_phone IS NOT NULL OR o.delivery_line1 IS NOT NULL"
          + " OR o.delivery_line2 IS NOT NULL OR o.delivery_city IS NOT NULL"
          + " OR o.delivery_postal_code IS NOT NULL OR o.delivery_recipient_name IS NOT NULL"
          + " OR o.delivery_recipient_phone IS NOT NULL OR o.notes IS NOT NULL)";

  /**
   * Records a customer's erasure and redacts everything that can go now, once per event.
   *
   * @return false for a redelivered event, which changes nothing
   */
  public boolean applyCustomerErasure(
      UUID tenantId, UUID customerId, UUID loginId, UUID eventId, String consumer) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO customer_erasures (tenant_id, customer_id, login_id, event_id)"
                      + " VALUES (?,?,?,?)"
                      + " ON CONFLICT (tenant_id, customer_id) DO UPDATE SET login_id ="
                      + " COALESCE(customer_erasures.login_id, EXCLUDED.login_id)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, customerId);
            ps.setObject(3, loginId);
            ps.setObject(4, eventId);
            ps.executeUpdate();
          }
          redactCustomerInTx(c, tenantId, customerId, loginId);
          return true;
        },
        "apply customer erasure");
  }

  /**
   * Both ids, everywhere an order is matched: the shop's customer id, and the login the same person
   * signed in with. An online order is filed under the login and a till sale under the customer, so
   * a redaction that knew only one of them left the other kind of sale identifying the person it
   * was erasing (SJ-D44). {@code o.login_id = ?} with a null parameter matches nothing, which is
   * exactly right for a walk-in that has no login.
   */
  private static final String ORDER_IS_THEIRS = " (o.customer_id = ? OR o.login_id = ?)";

  /** The same match, joined through the erasure row, for the cross-tenant sweep. */
  private static final String SWEPT_ORDER_IS_THEIRS =
      " (o.customer_id = e.customer_id OR o.login_id = e.login_id)";

  private static void redactCustomerInTx(Connection c, UUID tenantId, UUID customerId, UUID loginId)
      throws SQLException {
    // Settled orders only: an open delivery still needs its address to arrive.
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE orders o"
                + REDACT_ORDER
                + " WHERE o.tenant_id = ? AND"
                + ORDER_IS_THEIRS
                + " AND o.status IN "
                + SETTLED_ORDER
                + " AND"
                + ORDER_STILL_IDENTIFIES)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, loginId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            REDACT_SPECIAL_ORDER
                + " WHERE s.tenant_id = ? AND s.customer_id = ? AND"
                + SPECIAL_ORDER_SETTLED_AND_IDENTIFIES)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE layaways l SET notes = NULL WHERE l.tenant_id = ? AND l.customer_id = ? AND"
                + LAYAWAY_SETTLED_WITH_NOTES)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.executeUpdate();
    }
    // At once, whatever the order's state: the address a receipt was emailed to and the name on a
    // held basket are not needed to finish any sale.
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE order_receipts r SET emailed_to = NULL FROM orders o"
                + " WHERE r.tenant_id = ? AND o.tenant_id = r.tenant_id AND o.id = r.order_id"
                + " AND"
                + ORDER_IS_THEIRS
                + " AND r.emailed_to IS NOT NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, loginId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE parked_sales p SET customer_name = NULL, notes = NULL"
                + " WHERE p.tenant_id = ? AND p.customer_id = ?"
                + " AND (p.customer_name IS NOT NULL OR p.notes IS NOT NULL)")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.executeUpdate();
    }
  }

  /**
   * Redacts every order that has settled since its customer was erased. A cross-tenant sweep for
   * the background sweeper, like {@link #findExpiredPendingOrders}: the tenant is carried by the
   * join to customer_erasures on (tenant_id, customer_id), never assumed.
   *
   * <p>A sweep rather than a hook on each transition, because there are many ways for an order to
   * finish — fulfil, cancel, void, refund, the till's capture, the pending sweeper — and a hook
   * that one of them skipped would keep a forgotten customer's address forever.
   *
   * @return how many orders and special orders it redacted
   */
  public int sweepErasures() {
    return inTx(
        c -> {
          int redacted = 0;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders o"
                      + REDACT_ORDER
                      + " FROM customer_erasures e"
                      + " WHERE o.tenant_id = e.tenant_id AND"
                      + SWEPT_ORDER_IS_THEIRS
                      + " AND o.status IN "
                      + SETTLED_ORDER
                      + " AND"
                      + ORDER_STILL_IDENTIFIES)) {
            redacted += ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  REDACT_SPECIAL_ORDER
                      + " FROM customer_erasures e"
                      + " WHERE s.tenant_id = e.tenant_id AND s.customer_id = e.customer_id AND"
                      + SPECIAL_ORDER_SETTLED_AND_IDENTIFIES)) {
            redacted += ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE layaways l SET notes = NULL FROM customer_erasures e"
                      + " WHERE l.tenant_id = e.tenant_id AND l.customer_id = e.customer_id AND"
                      + LAYAWAY_SETTLED_WITH_NOTES)) {
            redacted += ps.executeUpdate();
          }
          // A receipt emailed after the erasure, for an order that was still open at the time.
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE order_receipts r SET emailed_to = NULL FROM orders o, customer_erasures e"
                      + " WHERE o.tenant_id = r.tenant_id AND o.id = r.order_id"
                      + " AND e.tenant_id = o.tenant_id AND"
                      + SWEPT_ORDER_IS_THEIRS
                      + " AND r.emailed_to IS NOT NULL")) {
            ps.executeUpdate();
          }
          return redacted;
        },
        "sweep customer erasures");
  }

  public record PendingOrderRef(UUID tenantId, UUID orderId) {}

  /**
   * PENDING orders older than {@code ttlHours}, oldest first — stranded pay-later orders that were
   * never paid (a paid one would have confirmed). Cross-tenant scan for the background sweeper
   * (each row carries its tenant), mirroring inventory-svc's reservation sweeper.
   */
  public List<PendingOrderRef> findExpiredPendingOrders(int ttlHours, int limit) {
    return query(
        "SELECT tenant_id, id FROM orders"
            + " WHERE status = 'PENDING' AND updated_at < now() - make_interval(hours => ?)"
            + " ORDER BY created_at ASC LIMIT ?",
        ps -> {
          ps.setInt(1, ttlHours);
          ps.setInt(2, limit);
        },
        rs ->
            new PendingOrderRef(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)),
        "find expired pending orders");
  }

  /**
   * Idempotently accumulates a captured payment toward an order's total, and confirms the order
   * (PENDING -&gt; CONFIRMED) once {@code paid_amount} reaches {@code total}. A single full-amount
   * tender confirms immediately, same as before; split tenders (e.g. POS cash+card, each below the
   * order total individually) now accumulate instead of each being silently dropped. Redelivery of
   * the same {@code paymentId} (golden rule #7) is a no-op via the unique key on {@code
   * order_payment_events}.
   */
  public boolean applyPaymentCaptured(
      UUID tenantId,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      OutboxRow confirmEvent,
      OutboxRow fulfilEvent) {
    // Returns true only when THIS capture completed the sale, so the caller numbers the receipt
    // exactly once. A redelivery, a partial tender and an order already past PENDING all return
    // false.
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO order_payment_events"
                      + " (tenant_id, payment_id, order_id, amount, method)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, paymentId);
            ps.setObject(3, orderId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, method);
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              return false; // already applied — event redelivery, no-op
            }
            throw sqle;
          }

          BigDecimal newPaid;
          BigDecimal total;
          String status;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET paid_amount = paid_amount + ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING paid_amount, total, status")) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return false; // order not found
              newPaid = rs.getBigDecimal("paid_amount");
              total = rs.getBigDecimal("total");
              status = rs.getString("status");
            }
          }

          if (!Order.STATUS_PENDING.equals(status) || newPaid.compareTo(total) < 0) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status='CONFIRMED', updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='PENDING'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            if (ps.executeUpdate() == 0) {
              return false;
            }
          }
          appendStatusHistory(
              c,
              tenantId,
              orderId,
              Order.STATUS_PENDING,
              Order.STATUS_CONFIRMED,
              "payment captured",
              null);
          insertOutbox(c, confirmEvent);

          // SJ-D40. A till sale is handed over at the counter the moment it is paid for, so the
          // capture that completes it also fulfils it — here, in the same transaction, so "paid for
          // and never deducted from stock" is not a state a crash between two calls can leave.
          if (fulfilEvent != null) {
            fulfilConfirmedInTx(c, tenantId, orderId, "sold at the till", null, fulfilEvent);
          }
          return true;
        },
        "apply payment captured");
  }

  /**
   * Confirms a till sale and hands it over in one transaction — the manual-confirm counterpart of
   * {@link #applyPaymentCaptured}, for a sale a manager confirms by hand.
   */
  public Order confirmAndFulfil(
      UUID tenantId, UUID orderId, UUID changedBy, OutboxRow confirmEvent, OutboxRow fulfilEvent) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status='CONFIRMED', updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='PENDING'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound(
                  "ORDER_NOT_FOUND_OR_WRONG_STATUS", "order not found or not in status PENDING");
            }
          }
          appendStatusHistory(
              c,
              tenantId,
              orderId,
              Order.STATUS_PENDING,
              Order.STATUS_CONFIRMED,
              "confirmed",
              changedBy);
          insertOutbox(c, confirmEvent);
          fulfilConfirmedInTx(c, tenantId, orderId, "sold at the till", changedBy, fulfilEvent);
          return findOrderInTx(c, tenantId, orderId);
        },
        "confirm and fulfil till sale " + orderId);
  }

  /**
   * CONFIRMED to FULFILLED, its history row and its OrderFulfilled event, on the caller's
   * connection. A no-op when the order is not CONFIRMED, so it cannot fulfil — and deduct stock for
   * — the same sale twice.
   */
  private void fulfilConfirmedInTx(
      java.sql.Connection c,
      UUID tenantId,
      UUID orderId,
      String reason,
      UUID changedBy,
      OutboxRow event)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE orders SET status='FULFILLED', updated_at=now()"
                + " WHERE tenant_id=? AND id=? AND status='CONFIRMED'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      if (ps.executeUpdate() == 0) {
        return;
      }
    }
    markAllLinesHandedOver(c, tenantId, orderId);
    appendStatusHistory(
        c, tenantId, orderId, Order.STATUS_CONFIRMED, Order.STATUS_FULFILLED, reason, changedBy);
    insertOutbox(c, event);
  }

  private static void markAllLinesHandedOver(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE order_items SET fulfilled_qty = qty WHERE tenant_id=? AND order_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.executeUpdate();
    }
  }

  /**
   * Prices an AWAITING_PRICE order (SJ-D41) in one transaction: every line gets its unit price and
   * line total, the order its subtotal, tax and total, and it moves to PENDING — from where it is
   * paid for like any other order. The sweeper counts its time-to-live from this moment, not from
   * the day it was placed.
   *
   * @param prices unit price per variant; every variant on the order must be present
   * @throws ApiException {@code ORDER_NOT_AWAITING_PRICE} (409); {@code ORDER_PRICE_LINE_MISSING}
   *     (400) when a line on the order was not priced; {@code ORDER_PRICE_LINE_UNKNOWN} (400)
   */
  public Order priceOrder(
      UUID tenantId, UUID orderId, Map<UUID, BigDecimal> prices, BigDecimal tax, UUID changedBy) {
    return inTx(
        c -> {
          String status;
          BigDecimal discount;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status, discount_amount FROM orders WHERE tenant_id=? AND id=? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
              }
              status = rs.getString(1);
              discount = rs.getBigDecimal(2) == null ? BigDecimal.ZERO : rs.getBigDecimal(2);
            }
          }
          if (!Order.STATUS_AWAITING_PRICE.equals(status)) {
            throw ApiException.conflict(
                "ORDER_NOT_AWAITING_PRICE",
                "only an order awaiting a price can be priced; this one is " + status);
          }
          List<OrderItem> items = new java.util.ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, order_id, variant_id, qty, unit_price, line_total, notes,"
                      + " weighing_instrument_id, fulfilled_qty, vat_amount FROM order_items"
                      + " WHERE tenant_id=? AND order_id=? ORDER BY created_at FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                items.add(mapOrderItem(rs));
              }
            }
          }
          for (UUID v : prices.keySet()) {
            if (items.stream().noneMatch(i -> i.variantId().equals(v))) {
              throw ApiException.badRequest(
                  "ORDER_PRICE_LINE_UNKNOWN", "variant " + v + " is not on this order");
            }
          }
          BigDecimal subtotal = BigDecimal.ZERO;
          for (OrderItem i : items) {
            BigDecimal price = prices.get(i.variantId());
            if (price == null) {
              throw ApiException.badRequest(
                  "ORDER_PRICE_LINE_MISSING", "no price given for variant " + i.variantId());
            }
            BigDecimal lineTotal =
                price.multiply(i.qty()).setScale(2, java.math.RoundingMode.HALF_UP);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE order_items SET unit_price=?, line_total=? WHERE tenant_id=? AND id=?")) {
              ps.setBigDecimal(1, price);
              ps.setBigDecimal(2, lineTotal);
              ps.setObject(3, tenantId);
              ps.setObject(4, i.id());
              ps.executeUpdate();
            }
            subtotal = subtotal.add(lineTotal);
          }
          BigDecimal total = subtotal.add(tax).subtract(discount).max(BigDecimal.ZERO);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET subtotal=?, tax_amount=?, total=?, status=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setBigDecimal(1, subtotal);
            ps.setBigDecimal(2, tax);
            ps.setBigDecimal(3, total);
            ps.setString(4, Order.STATUS_PENDING);
            ps.setObject(5, tenantId);
            ps.setObject(6, orderId);
            ps.executeUpdate();
          }
          appendStatusHistory(
              c,
              tenantId,
              orderId,
              Order.STATUS_AWAITING_PRICE,
              Order.STATUS_PENDING,
              "priced: total " + total.toPlainString(),
              changedBy);
          return findOrderInTx(c, tenantId, orderId);
        },
        "price order " + orderId);
  }

  /** One line's share of a fulfilment, for the event and the caller. */
  public record FulfilledLine(UUID variantId, BigDecimal qty) {}

  /**
   * Hands over part or all of what is outstanding on an order (SJ-D35), in one transaction: the
   * order row is locked, each requested quantity is checked against what its lines still owe and
   * added to their cumulative {@code fulfilled_qty}, the order moves to PARTIALLY_FULFILLED or —
   * once every line is complete — FULFILLED, the status history records how much, and the outbox
   * carries an OrderFulfilled with only this fulfilment's quantities, so inventory-svc deducts what
   * left the store now and nothing twice.
   *
   * @param tenantId owning tenant
   * @param orderId the order
   * @param wanted units per variant to hand over now; null or empty for everything outstanding
   * @param changedBy the staff member
   * @param eventFor builds the outbox row from the lines actually fulfilled now
   * @return the order as it now stands
   * @throws ApiException {@code ORDER_NOT_FULFILLABLE} (409) unless CONFIRMED or
   *     PARTIALLY_FULFILLED; {@code ORDER_FULFIL_LINE_UNKNOWN} (400) for a variant not on the
   *     order; {@code ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING} (409) for more than is still owed
   */
  public Order fulfilLines(
      UUID tenantId,
      UUID orderId,
      Map<UUID, BigDecimal> wanted,
      UUID changedBy,
      java.util.function.Function<List<FulfilledLine>, OutboxRow> eventFor) {
    return inTx(
        c -> {
          String status;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status FROM orders WHERE tenant_id=? AND id=? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              status = rs.next() ? rs.getString(1) : null;
            }
          }
          if (status == null) {
            throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
          }
          if (!Order.STATUS_CONFIRMED.equals(status)
              && !Order.STATUS_PARTIALLY_FULFILLED.equals(status)) {
            throw ApiException.conflict(
                "ORDER_NOT_FULFILLABLE",
                "only a CONFIRMED or PARTIALLY_FULFILLED order can be handed over; this one is "
                    + status);
          }
          List<OrderItem> items = new java.util.ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, order_id, variant_id, qty, unit_price, line_total, notes,"
                      + " weighing_instrument_id, fulfilled_qty, vat_amount FROM order_items"
                      + " WHERE tenant_id=? AND order_id=? ORDER BY created_at FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                items.add(mapOrderItem(rs));
              }
            }
          }
          // What is asked for, or everything outstanding when nothing is.
          Map<UUID, BigDecimal> ask = new java.util.LinkedHashMap<>();
          if (wanted == null || wanted.isEmpty()) {
            for (OrderItem i : items) {
              if (i.remainingQty().signum() > 0) {
                ask.merge(i.variantId(), i.remainingQty(), BigDecimal::add);
              }
            }
          } else {
            ask.putAll(wanted);
          }
          if (ask.isEmpty()) {
            throw ApiException.conflict(
                "ORDER_NOTHING_OUTSTANDING", "every line of this order has been handed over");
          }
          List<FulfilledLine> now = new java.util.ArrayList<>();
          for (var e : ask.entrySet()) {
            UUID variantId = e.getKey();
            boolean onOrder = items.stream().anyMatch(i -> i.variantId().equals(variantId));
            if (!onOrder) {
              throw ApiException.badRequest(
                  "ORDER_FULFIL_LINE_UNKNOWN", "variant " + variantId + " is not on this order");
            }
            BigDecimal outstanding =
                items.stream()
                    .filter(i -> i.variantId().equals(variantId))
                    .map(OrderItem::remainingQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (e.getValue().compareTo(outstanding) > 0) {
              throw ApiException.conflict(
                  "ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING",
                  "variant "
                      + variantId
                      + ": "
                      + e.getValue().stripTrailingZeros().toPlainString()
                      + " asked, "
                      + outstanding.stripTrailingZeros().toPlainString()
                      + " still outstanding");
            }
            // Spread the quantity over the lines of that variant, oldest first.
            BigDecimal left = e.getValue();
            for (OrderItem i : items) {
              if (left.signum() <= 0) {
                break;
              }
              if (!i.variantId().equals(variantId) || i.remainingQty().signum() <= 0) {
                continue;
              }
              BigDecimal take = left.min(i.remainingQty());
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "UPDATE order_items SET fulfilled_qty = fulfilled_qty + ?"
                          + " WHERE tenant_id=? AND id=? AND fulfilled_qty + ? <= qty")) {
                ps.setBigDecimal(1, take);
                ps.setObject(2, tenantId);
                ps.setObject(3, i.id());
                ps.setBigDecimal(4, take);
                if (ps.executeUpdate() == 0) {
                  throw ApiException.conflict(
                      "ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING", "line changed under this request");
                }
              }
              left = left.subtract(take);
            }
            now.add(new FulfilledLine(variantId, e.getValue()));
          }
          boolean complete = true;
          BigDecimal handed = BigDecimal.ZERO;
          BigDecimal ordered = BigDecimal.ZERO;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT SUM(qty) AS ordered, SUM(fulfilled_qty) AS handed,"
                      + " BOOL_AND(fulfilled_qty >= qty) AS complete"
                      + " FROM order_items WHERE tenant_id=? AND order_id=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                ordered = rs.getBigDecimal("ordered");
                handed = rs.getBigDecimal("handed");
                complete = rs.getBoolean("complete");
              }
            }
          }
          String next = complete ? Order.STATUS_FULFILLED : Order.STATUS_PARTIALLY_FULFILLED;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?")) {
            ps.setString(1, next);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.executeUpdate();
          }
          appendStatusHistory(
              c,
              tenantId,
              orderId,
              status,
              next,
              complete
                  ? "fulfilled"
                  : "part-fulfilled: "
                      + handed.stripTrailingZeros().toPlainString()
                      + " of "
                      + ordered.stripTrailingZeros().toPlainString()
                      + " units handed over",
              changedBy);
          insertOutbox(c, eventFor.apply(now));
          return findOrderInTx(c, tenantId, orderId);
        },
        "fulfil order " + orderId);
  }

  /**
   * Apply a PaymentRefunded event to the order, idempotently on {@code eventId}: accumulate {@code
   * refunded_amount} and move a sold order (CONFIRMED/FULFILLED, or a prior PARTIALLY_REFUNDED) to
   * REFUNDED once refunds reach the total, else PARTIALLY_REFUNDED. Cancelled/voided/pending orders
   * keep their status but still record the refunded amount. The dedupe mark, the update and the
   * status-history row commit in one transaction (golden rule #7).
   */
  public void applyRefundOnce(UUID eventId, UUID tenantId, UUID orderId, BigDecimal amount) {
    inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, REFUND_CONSUMER)) {
            return null; // this refund event already applied
          }
          String status;
          BigDecimal total;
          BigDecimal refunded;
          try (var ps =
              c.prepareStatement(
                  "SELECT status, total, refunded_amount FROM orders"
                      + " WHERE tenant_id=? AND id=? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (var rs = ps.executeQuery()) {
              if (!rs.next()) return null; // unknown order — nothing to apply
              status = rs.getString("status");
              total = rs.getBigDecimal("total");
              refunded = rs.getBigDecimal("refunded_amount");
            }
          }
          BigDecimal newRefunded = refunded.add(amount);
          String newStatus = status;
          if (Order.STATUS_CONFIRMED.equals(status)
              || Order.STATUS_FULFILLED.equals(status)
              || Order.STATUS_PARTIALLY_REFUNDED.equals(status)) {
            newStatus =
                newRefunded.compareTo(total) >= 0
                    ? Order.STATUS_REFUNDED
                    : Order.STATUS_PARTIALLY_REFUNDED;
          }
          try (var ps =
              c.prepareStatement(
                  "UPDATE orders SET refunded_amount=?, status=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setBigDecimal(1, newRefunded);
            ps.setString(2, newStatus);
            ps.setObject(3, tenantId);
            ps.setObject(4, orderId);
            ps.executeUpdate();
          }
          if (!newStatus.equals(status)) {
            appendStatusHistory(c, tenantId, orderId, status, newStatus, "refund", null);
          }
          return null;
        },
        "apply refund");
  }

  /**
   * Every order one person placed at this shop, newest first, for a data export (UK GDPR art.20).
   *
   * <p>Matched on both ids for the reason SJ-D44 records: an online sale is filed under the
   * shopper's login and a till sale under the shop's customer record, so a export that knew only
   * one of them would hand the person half their history and call it complete. A null parameter
   * matches nothing, which is what a walk-in with no login, or a login with no customer record,
   * should contribute.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the shop's record of the person, or {@code null}
   * @param loginId the login they sign in with, or {@code null}
   * @param limit hard cap on rows, so one export cannot read an unbounded table into memory
   * @return the person's orders, newest first
   */
  public List<Order> listOrdersForSubject(UUID tenantId, UUID customerId, UUID loginId, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, customer_id, login_id, channel, fulfilment_type, status,"
            + " subtotal, tax_amount, discount_amount, promotion_discount, total, currency, notes,"
            + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason,"
            + " delivery_line1, delivery_line2, delivery_city, delivery_postal_code,"
            + " delivery_recipient_name, delivery_recipient_phone, contact_phone, payment_method"
            + " FROM orders WHERE tenant_id=? AND (customer_id=? OR login_id=?)"
            + " ORDER BY created_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setObject(3, loginId);
          ps.setInt(4, limit);
        },
        rs -> mapOrder(rs),
        "list orders for subject");
  }

  /**
   * The lines on one order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose lines to read
   * @return the order's lines
   */
  public List<OrderItem> findOrderItems(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, variant_id, qty, unit_price, line_total,"
            + " notes, created_at, discount_amount, discount_reason, weighing_instrument_id,"
            + " fulfilled_qty, vat_amount"
            + " FROM order_items WHERE tenant_id=? AND order_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs -> mapOrderItem(rs),
        "find order items");
  }

  /**
   * The append-only status history of one order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose history to read
   * @return the transitions, oldest first
   */
  public List<OrderStatusHistory> findOrderHistory(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, from_status, to_status, reason, changed_by, changed_at"
            + " FROM order_status_history WHERE tenant_id=? AND order_id=? ORDER BY changed_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs -> mapHistory(rs),
        "find order history");
  }

  // ── Returns ───────────────────────────────────────────────────────────────

  /**
   * Records a return with its lines, moves the order's status and writes the event — atomically.
   *
   * @param ret the return header to persist
   * @param items the returned lines
   * @param event the outbox row to commit alongside; drives the refund in payment-svc and the
   *     restock in inventory-svc
   * @return the return as stored
   */
  public Return createReturn(Return ret, List<ReturnItem> items, OutboxRow event) {
    return inTx(
        c -> {
          // Lock each purchased line and re-check the cumulative returned quantity inside this
          // transaction so two concurrent returns on the same order can't jointly over-refund.
          for (ReturnItem item : items) {
            // What was handed over, not what was ordered: on a part-fulfilled order the rest never
            // left the store, and a refund for it is a refund for goods the customer never had.
            BigDecimal handedOverQty =
                lockOrderItemQty(c, ret.tenantId(), ret.orderId(), item.variantId());
            BigDecimal alreadyReturned =
                sumReturnedQty(c, ret.tenantId(), ret.orderId(), item.variantId());
            if (item.qty().add(alreadyReturned).compareTo(handedOverQty) > 0)
              throw ApiException.conflict(
                  "RETURN_QTY_EXCEEDS_PURCHASED",
                  "cannot return more than was handed over (and not yet returned) for variant "
                      + item.variantId());
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO returns"
                      + " (id,tenant_id,order_id,store_id,reason,refund_amount,refund_method,status)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, ret.id());
            ps.setObject(2, ret.tenantId());
            ps.setObject(3, ret.orderId());
            ps.setObject(4, ret.storeId());
            ps.setString(5, ret.reason());
            ps.setBigDecimal(6, ret.refundAmount());
            ps.setString(7, ret.refundMethod());
            ps.setString(8, ret.status());
            ps.executeUpdate();
          }
          for (ReturnItem item : items) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO return_items"
                        + " (id,tenant_id,return_id,variant_id,qty,refund_amount,condition)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
              ps.setObject(1, item.id());
              ps.setObject(2, item.tenantId());
              ps.setObject(3, item.returnId());
              ps.setObject(4, item.variantId());
              ps.setBigDecimal(5, item.qty());
              ps.setBigDecimal(6, item.refundAmount());
              ps.setString(7, item.condition());
              ps.executeUpdate();
            }
          }
          insertOutbox(c, event);
          return ret;
        },
        "create return");
  }

  /**
   * The returns recorded against one order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose returns to read
   * @return the returns, empty when nothing has come back
   */
  public List<Return> findReturns(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, store_id, reason, refund_amount, refund_method,"
            + " status, created_at, completed_at"
            + " FROM returns WHERE tenant_id=? AND order_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs -> mapReturn(rs),
        "find returns");
  }

  /**
   * The lines on one return.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param returnId the return whose lines to read
   * @return the returned lines with their per-line refund amounts
   */
  public List<ReturnItem> findReturnItems(UUID tenantId, UUID returnId) {
    return query(
        "SELECT id, tenant_id, return_id, variant_id, qty, refund_amount, condition"
            + " FROM return_items WHERE tenant_id=? AND return_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, returnId);
        },
        rs -> mapReturnItem(rs),
        "find return items");
  }

  // ── Post-void ─────────────────────────────────────────────────────────────

  /**
   * Voids a POS sale, restocking its lines and recording the void — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the sale to void
   * @param storeId the store the sale was rung in
   * @param reason free-text reason recorded on the void log
   * @param userId the staff member voiding it
   * @param eventFor builds the outbox row from the restock lines, which are only known once the
   *     order's items have been read inside the transaction
   * @return the recorded void log entry
   */
  public PosVoidLog voidOrder(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String reason,
      UUID voidedBy,
      java.util.function.Function<List<com.shelfj.order.domain.Domain.RestockLine>, OutboxRow>
          eventFor) {
    return inTx(
        c -> {
          // Lock first, so what is restocked is decided against the order being voided rather than
          // a copy read before a concurrent fulfil or return changed it.
          String priorStatus;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status FROM orders WHERE tenant_id=? AND id=? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              priorStatus = rs.next() ? rs.getString(1) : null;
            }
          }
          if (priorStatus == null
              || Order.STATUS_VOIDED.equals(priorStatus)
              || Order.STATUS_CANCELLED.equals(priorStatus)) {
            throw ApiException.conflict(
                "ORDER_CANNOT_VOID", "order not found or already voided/cancelled");
          }

          var restock = restockOnVoidInTx(c, tenantId, orderId);

          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?")) {
            ps.setString(1, Order.STATUS_VOIDED);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.executeUpdate();
          }
          PosVoidLog vl;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO pos_void_log (id,tenant_id,order_id,store_id,reason,voided_by)"
                      + " VALUES (?,?,?,?,?,?)")) {
            UUID vid = Ids.newId();
            ps.setObject(1, vid);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.setObject(4, storeId);
            ps.setString(5, reason);
            ps.setObject(6, voidedBy);
            ps.executeUpdate();
            vl = new PosVoidLog(vid, tenantId, orderId, storeId, reason, voidedBy, Instant.now());
          }
          // With the prior status in hand the history row says what was voided; it used to record
          // null here.
          appendStatusHistory(
              c, tenantId, orderId, priorStatus, Order.STATUS_VOIDED, reason, voidedBy);
          insertOutbox(c, eventFor.apply(restock));
          return vl;
        },
        "void order");
  }

  /**
   * What a void must put back: nothing unless the sale was handed over, and then each line net of
   * anything already returned (SJ-D40).
   *
   * <p>"Handed over" is read from the append-only status history rather than the current status: a
   * sold order moves on to PARTIALLY_REFUNDED or REFUNDED, and its current status alone forgets it
   * was ever fulfilled. Every return is netted whatever its status, because createReturn emits
   * OrderReturned — and so restocks — the moment a return is created.
   */
  private List<com.shelfj.order.domain.Domain.RestockLine> restockOnVoidInTx(
      Connection c, UUID tenantId, UUID orderId) throws SQLException {
    boolean handedOver = false;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM order_status_history"
                + " WHERE tenant_id=? AND order_id=?"
                + " AND to_status IN ('FULFILLED','PARTIALLY_FULFILLED'))")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          handedOver = rs.getBoolean(1);
        }
      }
    }
    if (!handedOver) {
      return List.of();
    }
    var lines = new java.util.ArrayList<com.shelfj.order.domain.Domain.RestockLine>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "WITH sold AS ("
                + "  SELECT variant_id, SUM(fulfilled_qty) AS qty FROM order_items"
                + "   WHERE tenant_id=? AND order_id=? GROUP BY variant_id"
                + "), returned AS ("
                + "  SELECT ri.variant_id, SUM(ri.qty) AS qty"
                + "    FROM return_items ri"
                + "    JOIN returns r ON r.id = ri.return_id AND r.tenant_id = ri.tenant_id"
                + "   WHERE r.tenant_id=? AND r.order_id=? GROUP BY ri.variant_id"
                + ")"
                + " SELECT s.variant_id, s.qty - COALESCE(rt.qty, 0) AS net"
                + "   FROM sold s LEFT JOIN returned rt ON rt.variant_id = s.variant_id"
                + "  WHERE s.qty - COALESCE(rt.qty, 0) > 0"
                + "  ORDER BY s.variant_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, tenantId);
      ps.setObject(4, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(
              new com.shelfj.order.domain.Domain.RestockLine(
                  rs.getObject(1, UUID.class), rs.getBigDecimal(2)));
        }
      }
    }
    return lines;
  }

  // ── Layaway ───────────────────────────────────────────────────────────────

  /**
   * Opens a layaway with its items and opening deposit — atomically.
   *
   * @param layaway the layaway header to persist
   * @param items the goods being set aside
   * @param deposit the opening payment, or {@code null} when none was taken
   * @return the layaway as stored
   */
  public Layaway createLayaway(
      Layaway layaway, List<LayawayItem> items, LayawayDeposit deposit, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO layaways"
                      + " (id,tenant_id,store_id,customer_id,total_amount,deposit_paid,balance,"
                      + "  status,notes,due_date)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, layaway.id());
            ps.setObject(2, layaway.tenantId());
            ps.setObject(3, layaway.storeId());
            ps.setObject(4, layaway.customerId());
            ps.setBigDecimal(5, layaway.totalAmount());
            ps.setBigDecimal(6, layaway.depositPaid());
            ps.setBigDecimal(7, layaway.balance());
            ps.setString(8, layaway.status());
            ps.setString(9, layaway.notes());
            ps.setObject(
                10, layaway.dueDate() != null ? java.sql.Timestamp.from(layaway.dueDate()) : null);
            ps.executeUpdate();
          }
          for (LayawayItem item : items) insertLayawayItem(c, item);
          insertLayawayDeposit(c, deposit);
          insertOutbox(c, event);
          return layaway;
        },
        "create layaway");
  }

  /**
   * Looks a layaway up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway to fetch
   * @return the layaway, or empty when it does not exist in this tenant
   */
  public Optional<Layaway> findLayaway(UUID tenantId, UUID layawayId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, customer_id, total_amount, deposit_paid,"
                + " balance, status, notes, due_date, created_at, updated_at,"
                + " completed_at, cancelled_at"
                + " FROM layaways WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, layawayId);
            },
            rs -> mapLayaway(rs),
            "find layaway");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Records a payment against a layaway and recomputes its balance — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway being paid down
   * @param deposit the payment to record
   * @return the layaway with its new balance
   */
  public Layaway addDeposit(UUID tenantId, UUID layawayId, LayawayDeposit deposit) {
    return inTx(
        c -> {
          int rows;
          // `AND balance >= ?` makes the overpayment check atomic with the update — without it a
          // deposit larger than the remaining balance was accepted unconditionally, driving
          // `balance` negative.
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE layaways"
                      + " SET deposit_paid = deposit_paid + ?,"
                      + "     balance = balance - ?,"
                      + "     updated_at = now()"
                      + " WHERE tenant_id=? AND id=? AND status='ACTIVE' AND balance >= ?")) {
            ps.setBigDecimal(1, deposit.amount());
            ps.setBigDecimal(2, deposit.amount());
            ps.setObject(3, tenantId);
            ps.setObject(4, layawayId);
            ps.setBigDecimal(5, deposit.amount());
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            // findLayawayInTx throws LAYAWAY_NOT_FOUND itself if the id doesn't exist at all.
            Layaway existing = findLayawayInTx(c, tenantId, layawayId);
            if (!Layaway.STATUS_ACTIVE.equals(existing.status())) {
              throw ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found or not active");
            }
            throw ApiException.badRequest(
                "LAYAWAY_DEPOSIT_EXCEEDS_BALANCE",
                "deposit "
                    + deposit.amount()
                    + " exceeds outstanding balance "
                    + existing.balance());
          }
          insertLayawayDeposit(c, deposit);
          return findLayawayInTx(c, tenantId, layawayId);
        },
        "add layaway deposit");
  }

  /**
   * Closes a fully paid layaway and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway to complete
   * @param event the outbox row to commit alongside
   * @return the completed layaway
   */
  public Layaway completeLayaway(UUID tenantId, UUID layawayId, OutboxRow event) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE layaways"
                      + " SET status='COMPLETED', completed_at=now(), updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='ACTIVE' AND balance<=0")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, layawayId);
            rows = ps.executeUpdate();
          }
          if (rows == 0)
            throw ApiException.conflict(
                "LAYAWAY_CANNOT_COMPLETE",
                "layaway not found, not active, or balance still outstanding");
          insertOutbox(c, event);
          return findLayawayInTx(c, tenantId, layawayId);
        },
        "complete layaway");
  }

  /**
   * Cancels a layaway and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway to cancel
   * @param reason free-text reason recorded against it
   * @param event the outbox row to commit alongside
   * @return the cancelled layaway
   */
  public Layaway cancelLayaway(UUID tenantId, UUID layawayId, String reason, OutboxRow event) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE layaways"
                      + " SET status='CANCELLED', cancelled_at=now(), updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='ACTIVE'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, layawayId);
            rows = ps.executeUpdate();
          }
          if (rows == 0)
            throw ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found or not active");
          insertOutbox(c, event);
          return findLayawayInTx(c, tenantId, layawayId);
        },
        "cancel layaway");
  }

  /**
   * The goods set aside on one layaway.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway whose items to read
   * @return the reserved lines with their prices
   */
  public List<LayawayItem> findLayawayItems(UUID tenantId, UUID layawayId) {
    return query(
        "SELECT id, tenant_id, layaway_id, variant_id, qty, unit_price, line_total"
            + " FROM layaway_items WHERE tenant_id=? AND layaway_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, layawayId);
        },
        rs -> mapLayawayItem(rs),
        "find layaway items");
  }

  /**
   * The payments made against one layaway.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param layawayId the layaway whose deposits to read
   * @return the payments, oldest first
   */
  public List<LayawayDeposit> findLayawayDeposits(UUID tenantId, UUID layawayId) {
    return query(
        "SELECT id, tenant_id, layaway_id, amount, payment_method, reference, paid_at"
            + " FROM layaway_deposits WHERE tenant_id=? AND layaway_id=? ORDER BY paid_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, layawayId);
        },
        rs -> mapLayawayDeposit(rs),
        "find layaway deposits");
  }

  // ── Gift cards ────────────────────────────────────────────────────────────

  /**
   * Issues a gift card and records its opening transaction — atomically.
   *
   * @param gc the card to persist, with its code and opening balance
   * @param tx the {@code ISSUE} transaction recording that balance
   * @return the card as stored
   */
  public GiftCard issueGiftCard(GiftCard gc, GiftCardTransaction tx) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO gift_cards"
                      + " (id,tenant_id,store_id,code,initial_balance,current_balance,"
                      + "  status,currency,expires_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, gc.id());
            ps.setObject(2, gc.tenantId());
            ps.setObject(3, gc.storeId());
            ps.setString(4, gc.code());
            ps.setBigDecimal(5, gc.initialBalance());
            ps.setBigDecimal(6, gc.currentBalance());
            ps.setString(7, gc.status());
            ps.setString(8, gc.currency());
            ps.setObject(
                9, gc.expiresAt() != null ? java.sql.Timestamp.from(gc.expiresAt()) : null);
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "GIFT_CARD_CODE_EXISTS",
                  "gift card code already in use",
                  java.util.List.of(),
                  sqle);
            throw sqle;
          }
          insertGiftCardTx(c, tx);
          return gc;
        },
        "issue gift card");
  }

  /**
   * Looks a gift card up by its code.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param code the card's code
   * @return the card, or empty when no such card exists in this tenant
   */
  public Optional<GiftCard> findGiftCardByCode(UUID tenantId, String code) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, code, initial_balance, current_balance,"
                + " status, currency, issued_at, updated_at, expires_at"
                + " FROM gift_cards WHERE tenant_id=? AND code=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
            },
            rs -> mapGiftCard(rs),
            "find gift card");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Adds value to a gift card and records the transaction — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param code the card's code
   * @param amount the amount to add
   * @param reference free-text reference recorded on the transaction
   * @return the card with its new balance
   * @throws com.shelfj.web.ApiException when the card does not exist or is not active
   */
  public GiftCard reloadGiftCard(UUID tenantId, String code, BigDecimal amount, String reference) {
    return inTx(
        c -> {
          GiftCard gc = findGiftCardByCodeInTx(c, tenantId, code);
          if (gc == null) throw ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found");
          if (!GiftCard.STATUS_ACTIVE.equals(gc.status()))
            throw ApiException.conflict("GIFT_CARD_NOT_ACTIVE", "gift card is not active");
          BigDecimal before = gc.currentBalance();
          BigDecimal after = before.add(amount);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE gift_cards SET current_balance=?, updated_at=now()"
                      + " WHERE tenant_id=? AND code=?")) {
            ps.setBigDecimal(1, after);
            ps.setObject(2, tenantId);
            ps.setString(3, code);
            ps.executeUpdate();
          }
          insertGiftCardTx(
              c,
              new GiftCardTransaction(
                  Ids.newId(),
                  tenantId,
                  gc.id(),
                  GiftCardTransaction.TX_RELOAD,
                  amount,
                  before,
                  after,
                  null,
                  reference,
                  Instant.now()));
          return findGiftCardByCodeInTx(c, tenantId, code);
        },
        "reload gift card");
  }

  /**
   * Redeem gift-card value toward an order. Idempotent per (card, order): a repeat redemption for
   * the same order returns the card unchanged rather than deducting again — the balance is money,
   * and a retried request whose response was lost must not charge the customer twice. This is the
   * same shape payment-svc uses to make a STORE_CREDIT tender idempotent, and it is what lets a POS
   * sale captured offline be replayed safely. A redemption with no orderId (a manual back-office
   * adjustment) has no natural key and is not deduplicated.
   */
  public GiftCard redeemGiftCard(
      UUID tenantId, String code, BigDecimal amount, UUID orderId, String reference) {
    return inTx(
        c -> {
          GiftCard gc = findGiftCardByCodeInTx(c, tenantId, code);
          if (gc == null) throw ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found");
          if (orderId != null && hasRedeemedForOrderTx(c, tenantId, gc.id(), orderId)) return gc;
          if (!GiftCard.STATUS_ACTIVE.equals(gc.status()))
            throw ApiException.conflict("GIFT_CARD_NOT_ACTIVE", "gift card is not active");
          if (gc.currentBalance().compareTo(amount) < 0)
            throw ApiException.conflict(
                "GIFT_CARD_INSUFFICIENT_BALANCE", "insufficient gift card balance");
          BigDecimal before = gc.currentBalance();
          BigDecimal after = before.subtract(amount);
          String newStatus =
              after.compareTo(BigDecimal.ZERO) == 0
                  ? GiftCard.STATUS_DEPLETED
                  : GiftCard.STATUS_ACTIVE;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE gift_cards SET current_balance=?, status=?, updated_at=now()"
                      + " WHERE tenant_id=? AND code=?")) {
            ps.setBigDecimal(1, after);
            ps.setString(2, newStatus);
            ps.setObject(3, tenantId);
            ps.setString(4, code);
            ps.executeUpdate();
          }
          insertGiftCardTx(
              c,
              new GiftCardTransaction(
                  Ids.newId(),
                  tenantId,
                  gc.id(),
                  GiftCardTransaction.TX_REDEEM,
                  amount,
                  before,
                  after,
                  orderId,
                  reference,
                  Instant.now()));
          return findGiftCardByCodeInTx(c, tenantId, code);
        },
        "redeem gift card");
  }

  /** True when this card has already been redeemed against this order (replay guard). */
  private static boolean hasRedeemedForOrderTx(
      java.sql.Connection c, UUID tenantId, UUID giftCardId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM gift_card_transactions"
                + " WHERE tenant_id=? AND gift_card_id=? AND order_id=? AND tx_type=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, giftCardId);
      ps.setObject(3, orderId);
      ps.setString(4, GiftCardTransaction.TX_REDEEM);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * The append-only transaction history of one gift card.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param giftCardId the card whose history to read
   * @return every issue, reload and redemption against the card
   */
  public List<GiftCardTransaction> findGiftCardTransactions(UUID tenantId, UUID giftCardId) {
    return query(
        "SELECT id, tenant_id, gift_card_id, tx_type, amount, balance_before,"
            + " balance_after, order_id, reference, created_at"
            + " FROM gift_card_transactions WHERE tenant_id=? AND gift_card_id=?"
            + " ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, giftCardId);
        },
        rs -> mapGiftCardTx(rs),
        "find gift card transactions");
  }

  // ── private helpers ───────────────────────────────────────────────────────

  private void insertOrderItem(Connection c, OrderItem item) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO order_items"
                + " (id,tenant_id,order_id,variant_id,qty,unit_price,line_total,notes,"
                + "  weighing_instrument_id, vat_amount)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, item.id());
      ps.setObject(2, item.tenantId());
      ps.setObject(3, item.orderId());
      ps.setObject(4, item.variantId());
      ps.setBigDecimal(5, item.qty());
      ps.setBigDecimal(6, item.unitPrice());
      ps.setBigDecimal(7, item.lineTotal());
      ps.setString(8, item.notes());
      ps.setObject(9, item.weighingInstrumentId());
      ps.setBigDecimal(10, item.vatAmount());
      ps.executeUpdate();
    }
  }

  private void appendStatusHistory(
      Connection c,
      UUID tenantId,
      UUID orderId,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO order_status_history"
                + " (id,tenant_id,order_id,from_status,to_status,reason,changed_by)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, orderId);
      ps.setString(4, fromStatus);
      ps.setString(5, toStatus);
      ps.setString(6, reason);
      ps.setObject(7, changedBy);
      ps.executeUpdate();
    }
  }

  private Order findOrderInTx(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, customer_id, login_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, promotion_discount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason,"
                + " delivery_line1, delivery_line2, delivery_city, delivery_postal_code,"
                + " delivery_recipient_name, delivery_recipient_phone, contact_phone, payment_method"
                + " FROM orders WHERE tenant_id=? AND id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
      return mapOrder(rs);
    }
  }

  private void insertLayawayItem(Connection c, LayawayItem item) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO layaway_items"
                + " (id,tenant_id,layaway_id,variant_id,qty,unit_price,line_total)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, item.id());
      ps.setObject(2, item.tenantId());
      ps.setObject(3, item.layawayId());
      ps.setObject(4, item.variantId());
      ps.setBigDecimal(5, item.qty());
      ps.setBigDecimal(6, item.unitPrice());
      ps.setBigDecimal(7, item.lineTotal());
      ps.executeUpdate();
    }
  }

  private void insertLayawayDeposit(Connection c, LayawayDeposit dep) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO layaway_deposits"
                + " (id,tenant_id,layaway_id,amount,payment_method,reference)"
                + " VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, dep.id());
      ps.setObject(2, dep.tenantId());
      ps.setObject(3, dep.layawayId());
      ps.setBigDecimal(4, dep.amount());
      ps.setString(5, dep.paymentMethod());
      ps.setString(6, dep.reference());
      ps.executeUpdate();
    }
  }

  private Layaway findLayawayInTx(Connection c, UUID tenantId, UUID layawayId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, customer_id, total_amount, deposit_paid,"
                + " balance, status, notes, due_date, created_at, updated_at,"
                + " completed_at, cancelled_at"
                + " FROM layaways WHERE tenant_id=? AND id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, layawayId);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) throw ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found");
      return mapLayaway(rs);
    }
  }

  private void insertGiftCardTx(Connection c, GiftCardTransaction tx) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO gift_card_transactions"
                + " (id,tenant_id,gift_card_id,tx_type,amount,balance_before,"
                + "  balance_after,order_id,reference)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, tx.id());
      ps.setObject(2, tx.tenantId());
      ps.setObject(3, tx.giftCardId());
      ps.setString(4, tx.txType());
      ps.setBigDecimal(5, tx.amount());
      ps.setBigDecimal(6, tx.balanceBefore());
      ps.setBigDecimal(7, tx.balanceAfter());
      ps.setObject(8, tx.orderId());
      ps.setString(9, tx.reference());
      ps.executeUpdate();
    }
  }

  private GiftCard findGiftCardByCodeInTx(Connection c, UUID tenantId, String code)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, code, initial_balance, current_balance,"
                + " status, currency, issued_at, updated_at, expires_at"
                + " FROM gift_cards WHERE tenant_id=? AND code=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setString(2, code);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) return null;
      return mapGiftCard(rs);
    }
  }

  /** Locks the purchased line so a concurrent return on the same variant serializes behind it. */
  private BigDecimal lockOrderItemQty(Connection c, UUID tenantId, UUID orderId, UUID variantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT fulfilled_qty AS qty FROM order_items"
                + " WHERE tenant_id=? AND order_id=? AND variant_id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound(
              "ITEM_NOT_IN_ORDER", "variant " + variantId + " not in order");
        return rs.getBigDecimal("qty");
      }
    }
  }

  private BigDecimal sumReturnedQty(Connection c, UUID tenantId, UUID orderId, UUID variantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(ri.qty), 0) AS total FROM return_items ri"
                + " JOIN returns r ON r.id = ri.return_id"
                + " WHERE r.tenant_id=? AND r.order_id=? AND ri.variant_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return BigDecimal.ZERO;
        return rs.getBigDecimal("total");
      }
    }
  }

  // ── mappers ───────────────────────────────────────────────────────────────

  private Order mapOrder(ResultSet rs) throws SQLException {
    return new Order(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getObject("login_id", UUID.class),
        rs.getString("channel"),
        rs.getString("fulfilment_type"),
        rs.getString("status"),
        rs.getBigDecimal("subtotal"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("discount_amount"),
        rs.getBigDecimal("total"),
        rs.getString("currency"),
        rs.getString("notes"),
        rs.getString("idempotency_key"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)),
        rs.getBoolean("tax_exempt"),
        rs.getString("exempt_reason"),
        rs.getString("delivery_line1"),
        rs.getString("delivery_line2"),
        rs.getString("delivery_city"),
        rs.getString("delivery_postal_code"),
        rs.getString("delivery_recipient_name"),
        rs.getString("delivery_recipient_phone"),
        rs.getString("contact_phone"),
        rs.getString("payment_method"),
        rs.getBigDecimal("promotion_discount"));
  }

  /**
   * Records which promotions applied to an order and for how much.
   *
   * <p>Append-only, and written in the same transaction as the order it belongs to, so a receipt
   * can never print a discount the order does not carry.
   */
  void insertOrderPromotionsTx(
      java.sql.Connection c,
      UUID tenantId,
      UUID orderId,
      List<com.shelfj.order.client.PricingClient.AppliedPromotion> applied)
      throws SQLException {
    if (applied.isEmpty()) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO order_promotions"
                + " (id, tenant_id, order_id, promotion_id, promotion_name, variant_id, amount)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      for (var a : applied) {
        ps.setObject(1, Ids.newId());
        ps.setObject(2, tenantId);
        ps.setObject(3, orderId);
        ps.setObject(4, a.promotionId());
        ps.setString(5, a.name());
        ps.setObject(6, a.variantId());
        ps.setBigDecimal(7, a.amount());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** What the promotion engine took off one order, for a receipt or a refund decision. */
  public List<com.shelfj.order.client.PricingClient.AppliedPromotion> findOrderPromotions(
      UUID tenantId, UUID orderId) {
    return query(
        "SELECT promotion_id, promotion_name, variant_id, amount FROM order_promotions"
            + " WHERE tenant_id = ? AND order_id = ? ORDER BY created_at, promotion_name",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs ->
            new com.shelfj.order.client.PricingClient.AppliedPromotion(
                rs.getObject("promotion_id", UUID.class),
                rs.getString("promotion_name"),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("amount")),
        "find order promotions");
  }

  private OrderItem mapOrderItem(ResultSet rs) throws SQLException {
    return new OrderItem(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("line_total"),
        rs.getString("notes"),
        rs.getObject("weighing_instrument_id", UUID.class),
        rs.getBigDecimal("fulfilled_qty"),
        rs.getBigDecimal("vat_amount"));
  }

  private OrderStatusHistory mapHistory(ResultSet rs) throws SQLException {
    return new OrderStatusHistory(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("reason"),
        rs.getObject("changed_by", UUID.class),
        toInstant(rs.getObject("changed_at", OffsetDateTime.class)));
  }

  private Return mapReturn(ResultSet rs) throws SQLException {
    return new Return(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("reason"),
        rs.getBigDecimal("refund_amount"),
        rs.getString("refund_method"),
        rs.getString("status"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("completed_at", OffsetDateTime.class)));
  }

  private ReturnItem mapReturnItem(ResultSet rs) throws SQLException {
    return new ReturnItem(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("return_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("refund_amount"),
        rs.getString("condition"));
  }

  private Layaway mapLayaway(ResultSet rs) throws SQLException {
    return new Layaway(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getBigDecimal("total_amount"),
        rs.getBigDecimal("deposit_paid"),
        rs.getBigDecimal("balance"),
        rs.getString("status"),
        rs.getString("notes"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("due_date", OffsetDateTime.class)),
        toInstant(rs.getObject("completed_at", OffsetDateTime.class)),
        toInstant(rs.getObject("cancelled_at", OffsetDateTime.class)));
  }

  private LayawayItem mapLayawayItem(ResultSet rs) throws SQLException {
    return new LayawayItem(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("layaway_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("line_total"));
  }

  private LayawayDeposit mapLayawayDeposit(ResultSet rs) throws SQLException {
    return new LayawayDeposit(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("layaway_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("payment_method"),
        rs.getString("reference"),
        toInstant(rs.getObject("paid_at", OffsetDateTime.class)));
  }

  private GiftCard mapGiftCard(ResultSet rs) throws SQLException {
    return new GiftCard(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("code"),
        rs.getBigDecimal("initial_balance"),
        rs.getBigDecimal("current_balance"),
        rs.getString("status"),
        rs.getString("currency"),
        toInstant(rs.getObject("issued_at", OffsetDateTime.class)),
        toInstant(rs.getObject("expires_at", OffsetDateTime.class)));
  }

  private GiftCardTransaction mapGiftCardTx(ResultSet rs) throws SQLException {
    return new GiftCardTransaction(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("gift_card_id", UUID.class),
        rs.getString("tx_type"),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("balance_before"),
        rs.getBigDecimal("balance_after"),
        rs.getObject("order_id", UUID.class),
        rs.getString("reference"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  /**
   * Opens a special order with its lines — atomically.
   *
   * @param so the special-order header to persist
   * @param items the lines being ordered in
   * @return the special order as stored
   */
  public SpecialOrder createSpecialOrder(SpecialOrder so, List<SpecialOrderItem> items) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO special_orders"
                      + " (id,tenant_id,store_id,customer_id,customer_name,customer_phone,"
                      + "  customer_email,delivery_address,requested_delivery_date,notes,status,"
                      + "  subtotal,total,currency,idempotency_key)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, so.id());
            ps.setObject(2, so.tenantId());
            ps.setObject(3, so.storeId());
            ps.setObject(4, so.customerId());
            ps.setString(5, so.customerName());
            ps.setString(6, so.customerPhone());
            ps.setString(7, so.customerEmail());
            ps.setString(8, so.deliveryAddress());
            ps.setObject(9, so.requestedDeliveryDate());
            ps.setString(10, so.notes());
            ps.setString(11, so.status());
            ps.setBigDecimal(12, so.subtotal());
            ps.setBigDecimal(13, so.total());
            ps.setString(14, so.currency());
            ps.setString(15, so.idempotencyKey());
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409, "SPECIAL_ORDER_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
            throw sqle;
          }
          for (var item : items) {
            try (var ps2 =
                c.prepareStatement(
                    "INSERT INTO special_order_items (id,tenant_id,so_id,variant_id,qty,unit_price,line_total,notes)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
              ps2.setObject(1, item.id());
              ps2.setObject(2, item.tenantId());
              ps2.setObject(3, item.soId());
              ps2.setObject(4, item.variantId());
              ps2.setBigDecimal(5, item.qty());
              ps2.setBigDecimal(6, item.unitPrice());
              ps2.setBigDecimal(7, item.lineTotal());
              ps2.setString(8, item.notes());
              ps2.executeUpdate();
            }
          }
          appendSpecialOrderHistory(c, so.tenantId(), so.id(), null, so.status(), "created", null);
          return so;
        },
        "create special order");
  }

  /**
   * Keyset-paginated: {@code afterCreatedAt}/{@code afterId} are the last-seen row's sort key (null
   * for the first page), and the caller fetches {@code limit + 1} rows to detect whether a further
   * page exists. Previously the store/customer-filtered branches had no limit at all and the
   * unfiltered branch was a flat {@code LIMIT 100} with no cursor — both silently truncated with no
   * way to see the rest.
   */
  public List<SpecialOrder> listSpecialOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
                + " customer_email, delivery_address, requested_delivery_date, notes, status,"
                + " subtotal, total, currency, idempotency_key, created_at, updated_at"
                + " FROM special_orders WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (customerId != null) sql.append(" AND customer_id=?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) < (?, ?)");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (customerId != null) ps.setObject(i++, customerId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapSpecialOrder,
        "list special orders");
  }

  /**
   * Looks a special order up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the special order to fetch
   * @return the special order, or empty when it does not exist in this tenant
   */
  public Optional<SpecialOrder> findSpecialOrder(UUID tenantId, UUID id) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
                + " customer_email, delivery_address, requested_delivery_date, notes, status,"
                + " subtotal, total, currency, idempotency_key, created_at, updated_at"
                + " FROM special_orders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapSpecialOrder,
            "find special order");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * The lines on one special order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param soId the special order whose lines to read
   * @return the ordered lines with their prices
   */
  public List<SpecialOrderItem> findSpecialOrderItems(UUID tenantId, UUID soId) {
    return query(
        "SELECT id, tenant_id, so_id, variant_id, qty, unit_price, line_total, notes"
            + " FROM special_order_items WHERE tenant_id=? AND so_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, soId);
        },
        this::mapSpecialOrderItem,
        "find special order items");
  }

  /**
   * Moves a special order between statuses, conditional on its current one.
   *
   * <p>Guarded on {@code fromStatus} for the same reason order transitions are: two callers racing
   * the same change cannot both win.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param soId the special order to move
   * @param fromStatus the status it must currently be in
   * @param toStatus the status to move it to
   * @param reason free-text reason recorded on the transition
   * @param userId the actor recorded on the transition
   * @return the special order in its new status
   * @throws com.shelfj.web.ApiException a conflict when it is no longer in {@code fromStatus}
   */
  public SpecialOrder transitionSpecialOrderStatus(
      UUID tenantId,
      UUID soId,
      String requiredFrom,
      String toStatus,
      String reason,
      UUID changedBy) {
    return inTx(
        c -> {
          SpecialOrder so = findSpecialOrderInTx(c, tenantId, soId);
          if (!requiredFrom.equals(so.status()))
            throw new ApiException(
                409,
                "SPECIAL_ORDER_INVALID_TRANSITION",
                "expected " + requiredFrom + " but was " + so.status(),
                List.of(),
                null);
          try (var ps =
              c.prepareStatement(
                  "UPDATE special_orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?")) {
            ps.setString(1, toStatus);
            ps.setObject(2, tenantId);
            ps.setObject(3, soId);
            ps.executeUpdate();
          }
          appendSpecialOrderHistory(c, tenantId, soId, so.status(), toStatus, reason, changedBy);
          return findSpecialOrderInTx(c, tenantId, soId);
        },
        "transition special order status");
  }

  private SpecialOrder findSpecialOrderInTx(Connection c, UUID tenantId, UUID soId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
                + " customer_email, delivery_address, requested_delivery_date, notes, status,"
                + " subtotal, total, currency, idempotency_key, created_at, updated_at"
                + " FROM special_orders WHERE tenant_id=? AND id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, soId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) return mapSpecialOrder(rs);
        throw new ApiException(
            404, "SPECIAL_ORDER_NOT_FOUND", "special order not found", List.of(), null);
      }
    }
  }

  private void appendSpecialOrderHistory(
      Connection c, UUID tenantId, UUID soId, String from, String to, String reason, UUID changedBy)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO special_order_status_history (id,tenant_id,so_id,from_status,to_status,reason,changed_by)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, soId);
      ps.setString(4, from);
      ps.setString(5, to);
      ps.setString(6, reason);
      ps.setObject(7, changedBy);
      ps.executeUpdate();
    }
  }

  private SpecialOrder mapSpecialOrder(ResultSet rs) throws SQLException {
    var rawDate = rs.getObject("requested_delivery_date");
    java.time.LocalDate delivDate = null;
    if (rawDate instanceof java.sql.Date sqlDate) delivDate = sqlDate.toLocalDate();
    return new SpecialOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("customer_name"),
        rs.getString("customer_phone"),
        rs.getString("customer_email"),
        rs.getString("delivery_address"),
        delivDate,
        rs.getString("notes"),
        rs.getString("status"),
        rs.getBigDecimal("subtotal"),
        rs.getBigDecimal("total"),
        rs.getString("currency"),
        rs.getString("idempotency_key"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private SpecialOrderItem mapSpecialOrderItem(ResultSet rs) throws SQLException {
    return new SpecialOrderItem(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("so_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("line_total"),
        rs.getString("notes"));
  }

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  /**
   * Appends one POSLog entry.
   *
   * @param e the entry to persist; its {@code id} must already be a UUIDv7
   * @return the entry as stored
   */
  public PosLogEntry insertPosLogEntry(PosLogEntry e) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO pos_log_entries"
                      + " (id,tenant_id,order_id,store_id,cashier_id,subtotal,tax_amount,discount_amount,"
                      + "  total,currency,tax_exempt,exempt_reason,transaction_ts)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, e.id());
            ps.setObject(2, e.tenantId());
            ps.setObject(3, e.orderId());
            ps.setObject(4, e.storeId());
            ps.setObject(5, e.cashierId());
            ps.setBigDecimal(6, e.subtotal());
            ps.setBigDecimal(7, e.taxAmount());
            ps.setBigDecimal(8, e.discountAmount());
            ps.setBigDecimal(9, e.total());
            ps.setString(10, e.currency());
            ps.setBoolean(11, e.taxExempt());
            ps.setString(12, e.exemptReason());
            ps.setObject(13, e.transactionTs().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "POSLOG_DUPLICATE",
                  "POSLog entry already exists for this order",
                  List.of(),
                  sqle);
            throw sqle;
          }
          return e;
        },
        "insert pos log entry");
  }

  /**
   * Journal a completed POS sale, returning the existing entry if this order is already journalled
   * rather than failing.
   *
   * <p>The till calls this after taking the money, so it is on the retry path: a lost response, or
   * a sale captured offline and replayed later, must not turn into an error the cashier has to
   * interpret. The order id is the natural key and already carries a unique index, so this is the
   * same shape as the gift-card redeem guard — a replay is a no-op, not a 409.
   */
  public PosLogEntry recordPosLogOnce(PosLogEntry e) {
    return inTx(
        c -> {
          PosLogEntry existing = findPosLogByOrderTx(c, e.tenantId(), e.orderId());
          if (existing != null) return existing;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO pos_log_entries"
                      + " (id,tenant_id,order_id,store_id,cashier_id,subtotal,tax_amount,"
                      + "  discount_amount,total,currency,tax_exempt,exempt_reason,transaction_ts)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, e.id());
            ps.setObject(2, e.tenantId());
            ps.setObject(3, e.orderId());
            ps.setObject(4, e.storeId());
            ps.setObject(5, e.cashierId());
            ps.setBigDecimal(6, e.subtotal());
            ps.setBigDecimal(7, e.taxAmount());
            ps.setBigDecimal(8, e.discountAmount());
            ps.setBigDecimal(9, e.total());
            ps.setString(10, e.currency());
            ps.setBoolean(11, e.taxExempt());
            ps.setString(12, e.exemptReason());
            ps.setObject(13, e.transactionTs().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            // Two tills journalling the same order at once: the loser re-reads the winner's row.
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              PosLogEntry raced = findPosLogByOrderTx(c, e.tenantId(), e.orderId());
              if (raced != null) return raced;
            }
            throw sqle;
          }
          return e;
        },
        "record pos log entry");
  }

  private PosLogEntry findPosLogByOrderTx(java.sql.Connection c, UUID tenantId, UUID orderId)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, store_id, cashier_id, subtotal, tax_amount,"
                + " discount_amount, total, currency, tax_exempt, exempt_reason,"
                + " transaction_ts, created_at"
                + " FROM pos_log_entries WHERE tenant_id=? AND order_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapPosLogEntry(rs) : null;
      }
    }
  }

  // ── Staff exception report ──────────────────────────────────────────────────
  //
  // Four separate aggregates rather than one joined query, because these are four
  // independent append-only logs with no join key between them beyond the actor or store
  // they name. Joining them would multiply rows: a cashier with 3 discounts and 2 voids
  // would report 6 of each. They are summed separately and merged on the key in the
  // service, which is also what lets a cashier who only appears in one log still get a row.

  /** discounts: count and total value, keyed by actor or store. */
  public List<Object[]> aggregateDiscounts(
      UUID tenantId, UUID storeId, Instant from, Instant to, boolean byActor) {
    String key = byActor ? "granted_by" : "store_id";
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + key
                + ", COUNT(*), COALESCE(SUM(discount_amount),0)"
                + " FROM order_discounts WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (from != null) sql.append(" AND created_at >= ?");
    if (to != null) sql.append(" AND created_at < ?");
    sql.append(" GROUP BY ").append(key);
    return query(
        sql.toString(),
        ps -> bindPeriod(ps, tenantId, storeId, from, to),
        rs -> new Object[] {rs.getObject(1), rs.getLong(2), rs.getBigDecimal(3)},
        "aggregate discounts");
  }

  /** voids: count only — a void has no money on it, only an order it removed. */
  public List<Object[]> aggregateVoids(
      UUID tenantId, UUID storeId, Instant from, Instant to, boolean byActor) {
    String key = byActor ? "voided_by" : "store_id";
    StringBuilder sql =
        new StringBuilder("SELECT " + key + ", COUNT(*) FROM pos_void_log WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (from != null) sql.append(" AND voided_at >= ?");
    if (to != null) sql.append(" AND voided_at < ?");
    sql.append(" GROUP BY ").append(key);
    return query(
        sql.toString(),
        ps -> bindPeriod(ps, tenantId, storeId, from, to),
        rs -> new Object[] {rs.getObject(1), rs.getLong(2)},
        "aggregate voids");
  }

  /** no-sales: drawer opened with no transaction. */
  public List<Object[]> aggregateNoSales(
      UUID tenantId, UUID storeId, Instant from, Instant to, boolean byActor) {
    String key = byActor ? "cashier_id" : "store_id";
    StringBuilder sql =
        new StringBuilder("SELECT " + key + ", COUNT(*) FROM pos_no_sale_log WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (from != null) sql.append(" AND logged_at >= ?");
    if (to != null) sql.append(" AND logged_at < ?");
    sql.append(" GROUP BY ").append(key);
    return query(
        sql.toString(),
        ps -> bindPeriod(ps, tenantId, storeId, from, to),
        rs -> new Object[] {rs.getObject(1), rs.getLong(2)},
        "aggregate no-sales");
  }

  /** The denominator: journalled sales, so exceptions can be read as a rate. */
  public List<Object[]> aggregateJournalledSales(
      UUID tenantId, UUID storeId, Instant from, Instant to, boolean byActor) {
    String key = byActor ? "cashier_id" : "store_id";
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + key
                + ", COUNT(*), COALESCE(SUM(total),0)"
                + " FROM pos_log_entries WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (from != null) sql.append(" AND transaction_ts >= ?");
    if (to != null) sql.append(" AND transaction_ts < ?");
    sql.append(" GROUP BY ").append(key);
    return query(
        sql.toString(),
        ps -> bindPeriod(ps, tenantId, storeId, from, to),
        rs -> new Object[] {rs.getObject(1), rs.getLong(2), rs.getBigDecimal(3)},
        "aggregate journalled sales");
  }

  /** tenant_id first (golden rule #3), then the optional store and period, in SQL order. */
  private static void bindPeriod(
      PreparedStatement ps, UUID tenantId, UUID storeId, Instant from, Instant to)
      throws java.sql.SQLException {
    int i = 1;
    ps.setObject(i++, tenantId);
    if (storeId != null) ps.setObject(i++, storeId);
    if (from != null) ps.setObject(i++, from.atOffset(java.time.ZoneOffset.UTC));
    if (to != null) ps.setObject(i++, to.atOffset(java.time.ZoneOffset.UTC));
  }

  /**
   * The POSLog entries recorded against one sale.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the sale whose entries to read
   * @return the entries, empty when the sale was not rung on a till
   */
  public List<PosLogEntry> findPosLogByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, store_id, cashier_id, subtotal, tax_amount,"
            + " discount_amount, total, currency, tax_exempt, exempt_reason,"
            + " transaction_ts, created_at"
            + " FROM pos_log_entries WHERE tenant_id=? AND order_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapPosLogEntry,
        "find pos log by order");
  }

  /**
   * Keyset-paginated on {@code (transaction_ts, id)}; previously a flat {@code LIMIT 200} with no
   * cursor, silently truncating a busy store's log with no way to see the rest.
   */
  public List<PosLogEntry> listPosLog(
      UUID tenantId, UUID storeId, Instant afterTransactionTs, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, order_id, store_id, cashier_id, subtotal, tax_amount,"
                + " discount_amount, total, currency, tax_exempt, exempt_reason,"
                + " transaction_ts, created_at"
                + " FROM pos_log_entries WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (afterTransactionTs != null && afterId != null) {
      sql.append(" AND (transaction_ts, id) < (?, ?)");
    }
    sql.append(" ORDER BY transaction_ts DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (afterTransactionTs != null && afterId != null) {
            ps.setObject(i++, afterTransactionTs.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapPosLogEntry,
        "list pos log");
  }

  // ─────────────────────────────────────────── age verification (append-only)

  /**
   * Writes one age check. Never updated or deleted: a wrong record is answered by another record.
   *
   * @param v the check, with its id already minted
   * @return the record as stored
   */
  public AgeVerification recordAgeVerification(AgeVerification v) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO age_verifications"
                      + " (id, tenant_id, store_id, cashier_id, pos_session_id, variant_id,"
                      + "  category, minimum_age, country, store_policy, outcome, reason,"
                      + "  id_type, order_id, checked_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, v.id());
            ps.setObject(2, v.tenantId());
            ps.setObject(3, v.storeId());
            ps.setObject(4, v.cashierId());
            ps.setObject(5, v.posSessionId());
            ps.setObject(6, v.variantId());
            ps.setString(7, v.category());
            ps.setInt(8, v.minimumAge());
            ps.setString(9, v.country());
            ps.setBoolean(10, v.storePolicy());
            ps.setString(11, v.outcome());
            ps.setString(12, v.reason());
            ps.setString(13, v.idType());
            ps.setObject(14, v.orderId());
            ps.setObject(15, v.checkedAt().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return v;
        },
        "record age verification");
  }

  /**
   * Keyset page of age checks, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId restrict to one store, or {@code null}
   * @param outcome restrict to PASSED or REFUSED, or {@code null}
   * @param from inclusive lower bound on the check time, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @param afterCheckedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id
   * @param limit maximum rows; callers pass one more than the page size
   * @return the page
   */
  public List<AgeVerification> listAgeVerifications(
      UUID tenantId,
      UUID storeId,
      String outcome,
      Instant from,
      Instant to,
      Instant afterCheckedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, cashier_id, pos_session_id, variant_id, category,"
                + " minimum_age, country, store_policy, outcome, reason, id_type, order_id,"
                + " checked_at FROM age_verifications WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (outcome != null) sql.append(" AND outcome=?");
    if (from != null) sql.append(" AND checked_at >= ?");
    if (to != null) sql.append(" AND checked_at < ?");
    if (afterCheckedAt != null && afterId != null) sql.append(" AND (checked_at, id) < (?, ?)");
    sql.append(" ORDER BY checked_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (outcome != null) ps.setString(i++, outcome);
          if (from != null) ps.setObject(i++, from.atOffset(java.time.ZoneOffset.UTC));
          if (to != null) ps.setObject(i++, to.atOffset(java.time.ZoneOffset.UTC));
          if (afterCheckedAt != null && afterId != null) {
            ps.setObject(i++, afterCheckedAt.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        OrderRepository::mapAgeVerification,
        "list age verifications");
  }

  /**
   * Counts for a store (or the tenant) over a period: total, passed, refused, refusals by reason
   * and checks by category.
   *
   * @param tenantId owning tenant; the first condition of every query
   * @param storeId one store, or {@code null} for all
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @return the counts
   */
  public AgeVerificationSummary summariseAgeVerifications(
      UUID tenantId, UUID storeId, Instant from, Instant to) {
    StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
    if (storeId != null) where.append(" AND store_id=?");
    if (from != null) where.append(" AND checked_at >= ?");
    if (to != null) where.append(" AND checked_at < ?");
    java.util.function.Consumer<PreparedStatement> bind =
        ps -> {
          try {
            int i = 1;
            ps.setObject(i++, tenantId);
            if (storeId != null) ps.setObject(i++, storeId);
            if (from != null) ps.setObject(i++, from.atOffset(java.time.ZoneOffset.UTC));
            if (to != null) ps.setObject(i, to.atOffset(java.time.ZoneOffset.UTC));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        };
    List<Object[]> outcomes =
        query(
            "SELECT outcome, count(*) FROM age_verifications" + where + " GROUP BY outcome",
            bind::accept,
            rs -> new Object[] {rs.getString(1), rs.getLong(2)},
            "summarise age verifications by outcome");
    List<Object[]> reasons =
        query(
            "SELECT reason, count(*) FROM age_verifications"
                + where
                + " AND reason IS NOT NULL GROUP BY reason",
            bind::accept,
            rs -> new Object[] {rs.getString(1), rs.getLong(2)},
            "summarise age verifications by reason");
    List<Object[]> categories =
        query(
            "SELECT category, count(*) FROM age_verifications" + where + " GROUP BY category",
            bind::accept,
            rs -> new Object[] {rs.getString(1), rs.getLong(2)},
            "summarise age verifications by category");
    long passed = 0;
    long refused = 0;
    for (Object[] row : outcomes) {
      if (AgeVerification.OUTCOME_PASSED.equals(row[0])) passed = (Long) row[1];
      if (AgeVerification.OUTCOME_REFUSED.equals(row[0])) refused = (Long) row[1];
    }
    java.util.Map<String, Long> byReason = new java.util.TreeMap<>();
    for (Object[] row : reasons) byReason.put((String) row[0], (Long) row[1]);
    java.util.Map<String, Long> byCategory = new java.util.TreeMap<>();
    for (Object[] row : categories) byCategory.put((String) row[0], (Long) row[1]);
    return new AgeVerificationSummary(passed + refused, passed, refused, byReason, byCategory);
  }

  private static AgeVerification mapAgeVerification(ResultSet rs) throws SQLException {
    return new AgeVerification(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("cashier_id", UUID.class),
        rs.getObject("pos_session_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("category"),
        rs.getInt("minimum_age"),
        rs.getString("country"),
        rs.getBoolean("store_policy"),
        rs.getString("outcome"),
        rs.getString("reason"),
        rs.getString("id_type"),
        rs.getObject("order_id", UUID.class),
        toInstant(rs.getObject("checked_at", OffsetDateTime.class)));
  }

  private PosLogEntry mapPosLogEntry(ResultSet rs) throws SQLException {
    return new PosLogEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("cashier_id", UUID.class),
        rs.getBigDecimal("subtotal"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("discount_amount"),
        rs.getBigDecimal("total"),
        rs.getString("currency"),
        rs.getBoolean("tax_exempt"),
        rs.getString("exempt_reason"),
        toInstant(rs.getObject("transaction_ts", OffsetDateTime.class)),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  /**
   * Appends one print/email receipt event.
   *
   * @param r the receipt event to persist; its {@code id} must already be a UUIDv7
   * @return the event as stored
   */
  public OrderReceipt insertOrderReceipt(OrderReceipt r) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO order_receipts (id,tenant_id,order_id,receipt_type,emailed_to,print_count)"
                      + " VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.orderId());
            ps.setString(4, r.receiptType());
            ps.setString(5, r.emailedTo());
            ps.setInt(6, r.printCount());
            ps.executeUpdate();
          }
          return r;
        },
        "insert order receipt");
  }

  /**
   * Every print/email receipt event recorded against one sale.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the sale whose receipt events to read
   * @return the events, empty when no copy was ever produced
   */
  public List<OrderReceipt> findOrderReceipts(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, receipt_type, emailed_to, print_count, generated_at"
            + " FROM order_receipts WHERE tenant_id=? AND order_id=? ORDER BY generated_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapOrderReceipt,
        "find order receipts");
  }

  private OrderReceipt mapOrderReceipt(ResultSet rs) throws SQLException {
    return new OrderReceipt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getString("receipt_type"),
        rs.getString("emailed_to"),
        rs.getInt("print_count"),
        toInstant(rs.getObject("generated_at", OffsetDateTime.class)));
  }

  private static Instant toInstant(OffsetDateTime odt) {
    return odt == null ? null : odt.toInstant();
  }

  // ── Gap #50: SIM ↔ POS sync — stock position projection ──────────────────

  /**
   * Upsert the local stock-position projection for one (store, variant), deduped on eventId. Delta
   * is signed: positive for receipts/returns, negative for deductions. The processed_events mark
   * and the (non-idempotent, additive) upsert commit in ONE transaction so a redelivered event is
   * skipped and a crashed write is retried — never applied twice and never lost. Returns false if
   * the event was already processed.
   */
  public boolean upsertStockPositionOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      java.math.BigDecimal delta) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO pos_stock_positions"
                      + " (tenant_id, store_id, variant_id, on_hand_qty, updated_at)"
                      + " VALUES (?,?,?,?,now())"
                      + " ON CONFLICT (tenant_id, store_id, variant_id) DO UPDATE"
                      + " SET on_hand_qty = pos_stock_positions.on_hand_qty + excluded.on_hand_qty,"
                      + "     updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            ps.setBigDecimal(4, delta);
            ps.executeUpdate();
          }
          return true;
        },
        "upsert stock position");
  }

  /**
   * On-hand positions from order-svc's own projection of inventory events.
   *
   * <p>Not a read of inventory-svc's tables: this is a local projection, so the figures are
   * eventually consistent with the owning service.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId restrict to one store, or {@code null}
   * @param variantId restrict to one variant, or {@code null}
   * @param limit maximum rows
   * @return the stock positions
   */
  public List<com.shelfj.order.domain.Domain.PosStockPosition> findStockPositions(
      UUID tenantId, UUID storeId, UUID variantId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT tenant_id, store_id, variant_id, on_hand_qty, updated_at"
                + " FROM pos_stock_positions WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (variantId != null) sql.append(" AND variant_id=?");
    sql.append(" ORDER BY store_id, variant_id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          ps.setInt(i, limit);
        },
        rs ->
            new com.shelfj.order.domain.Domain.PosStockPosition(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("on_hand_qty"),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class))),
        "find stock positions");
  }
}
