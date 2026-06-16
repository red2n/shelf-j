package com.shelfj.order.repo;

import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
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
import java.util.Optional;
import java.util.UUID;

/** Order persistence (JDBC). Every query filters tenant_id first. */
@ApplicationScoped
public class OrderRepository extends BaseOutboxRepository {

  // ── Orders ────────────────────────────────────────────────────────────────

  public Order createOrder(Order order, List<OrderItem> items, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO orders"
                      + " (id,tenant_id,store_id,customer_id,channel,fulfilment_type,status,"
                      + "  subtotal,tax_amount,discount_amount,total,currency,notes,idempotency_key,"
                      + "  tax_exempt,exempt_reason)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, order.id());
            ps.setObject(2, order.tenantId());
            ps.setObject(3, order.storeId());
            ps.setObject(4, order.customerId());
            ps.setString(5, order.channel());
            ps.setString(6, order.fulfilmentType());
            ps.setString(7, order.status());
            ps.setBigDecimal(8, order.subtotal());
            ps.setBigDecimal(9, order.taxAmount());
            ps.setBigDecimal(10, order.discountAmount());
            ps.setBigDecimal(11, order.total());
            ps.setString(12, order.currency());
            ps.setString(13, order.notes());
            ps.setString(14, order.idempotencyKey());
            ps.setBoolean(15, order.taxExempt());
            ps.setString(16, order.exemptReason());
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
          insertOutbox(c, event);
          return order;
        },
        "create order");
  }

  /** Look up an order by its idempotency key — used to replay a retried checkout. */
  public Optional<Order> findOrderByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return query(
            "SELECT id, tenant_id, store_id, customer_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason"
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

  public List<Order> listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      String channel,
      String status,
      Instant from,
      Instant to,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, customer_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason"
                + " FROM orders WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (customerId != null) sql.append(" AND customer_id=?");
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

  public Optional<Order> findOrder(UUID tenantId, UUID orderId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, customer_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason"
                + " FROM orders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            rs -> mapOrder(rs),
            "find order");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

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

  public List<OrderItem> findOrderItems(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, variant_id, qty, unit_price, line_total,"
            + " notes, created_at, discount_amount, discount_reason"
            + " FROM order_items WHERE tenant_id=? AND order_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs -> mapOrderItem(rs),
        "find order items");
  }

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

  public Return createReturn(Return ret, List<ReturnItem> items, OutboxRow event) {
    return inTx(
        c -> {
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

  public PosVoidLog voidOrder(
      UUID tenantId, UUID orderId, UUID storeId, String reason, UUID voidedBy, OutboxRow event) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET status=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status NOT IN ('VOIDED','CANCELLED')")) {
            ps.setString(1, Order.STATUS_VOIDED);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            rows = ps.executeUpdate();
          }
          if (rows == 0)
            throw ApiException.conflict(
                "ORDER_CANNOT_VOID", "order not found or already voided/cancelled");
          PosVoidLog vl;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO pos_void_log (id,tenant_id,order_id,store_id,reason,voided_by)"
                      + " VALUES (?,?,?,?,?,?)")) {
            UUID vid = UUID.randomUUID();
            ps.setObject(1, vid);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.setObject(4, storeId);
            ps.setString(5, reason);
            ps.setObject(6, voidedBy);
            ps.executeUpdate();
            vl = new PosVoidLog(vid, tenantId, orderId, storeId, reason, voidedBy, Instant.now());
          }
          appendStatusHistory(c, tenantId, orderId, null, Order.STATUS_VOIDED, reason, voidedBy);
          insertOutbox(c, event);
          return vl;
        },
        "void order");
  }

  // ── Layaway ───────────────────────────────────────────────────────────────

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

  public Layaway addDeposit(UUID tenantId, UUID layawayId, LayawayDeposit deposit) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE layaways"
                      + " SET deposit_paid = deposit_paid + ?,"
                      + "     balance = balance - ?,"
                      + "     updated_at = now()"
                      + " WHERE tenant_id=? AND id=? AND status='ACTIVE'")) {
            ps.setBigDecimal(1, deposit.amount());
            ps.setBigDecimal(2, deposit.amount());
            ps.setObject(3, tenantId);
            ps.setObject(4, layawayId);
            rows = ps.executeUpdate();
          }
          if (rows == 0)
            throw ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found or not active");
          insertLayawayDeposit(c, deposit);
          return findLayawayInTx(c, tenantId, layawayId);
        },
        "add layaway deposit");
  }

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
                  UUID.randomUUID(),
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

  public GiftCard redeemGiftCard(
      UUID tenantId, String code, BigDecimal amount, UUID orderId, String reference) {
    return inTx(
        c -> {
          GiftCard gc = findGiftCardByCodeInTx(c, tenantId, code);
          if (gc == null) throw ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found");
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
                  UUID.randomUUID(),
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
                + " (id,tenant_id,order_id,variant_id,qty,unit_price,line_total,notes)"
                + " VALUES (?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, item.id());
      ps.setObject(2, item.tenantId());
      ps.setObject(3, item.orderId());
      ps.setObject(4, item.variantId());
      ps.setBigDecimal(5, item.qty());
      ps.setBigDecimal(6, item.unitPrice());
      ps.setBigDecimal(7, item.lineTotal());
      ps.setString(8, item.notes());
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
      ps.setObject(1, UUID.randomUUID());
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
            "SELECT id, tenant_id, store_id, customer_id, channel, fulfilment_type, status,"
                + " subtotal, tax_amount, discount_amount, total, currency, notes,"
                + " idempotency_key, created_at, updated_at, tax_exempt, exempt_reason"
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

  // ── mappers ───────────────────────────────────────────────────────────────

  private Order mapOrder(ResultSet rs) throws SQLException {
    return new Order(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
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
        rs.getString("exempt_reason"));
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
        rs.getString("notes"));
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

  public List<SpecialOrder> listSpecialOrders(UUID tenantId, UUID storeId, UUID customerId) {
    if (storeId != null) {
      return query(
          "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
              + " customer_email, delivery_address, requested_delivery_date, notes, status,"
              + " subtotal, total, currency, idempotency_key, created_at, updated_at"
              + " FROM special_orders WHERE tenant_id=? AND store_id=? ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
          },
          this::mapSpecialOrder,
          "list special orders by store");
    }
    if (customerId != null) {
      return query(
          "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
              + " customer_email, delivery_address, requested_delivery_date, notes, status,"
              + " subtotal, total, currency, idempotency_key, created_at, updated_at"
              + " FROM special_orders WHERE tenant_id=? AND customer_id=? ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, customerId);
          },
          this::mapSpecialOrder,
          "list special orders by customer");
    }
    return query(
        "SELECT id, tenant_id, store_id, customer_id, customer_name, customer_phone,"
            + " customer_email, delivery_address, requested_delivery_date, notes, status,"
            + " subtotal, total, currency, idempotency_key, created_at, updated_at"
            + " FROM special_orders WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
        ps -> ps.setObject(1, tenantId),
        this::mapSpecialOrder,
        "list special orders");
  }

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
      ps.setObject(1, UUID.randomUUID());
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

  public List<PosLogEntry> listPosLog(UUID tenantId, UUID storeId) {
    if (storeId != null) {
      return query(
          "SELECT id, tenant_id, order_id, store_id, cashier_id, subtotal, tax_amount,"
              + " discount_amount, total, currency, tax_exempt, exempt_reason,"
              + " transaction_ts, created_at"
              + " FROM pos_log_entries WHERE tenant_id=? AND store_id=? ORDER BY transaction_ts DESC LIMIT 200",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
          },
          this::mapPosLogEntry,
          "list pos log by store");
    }
    return query(
        "SELECT id, tenant_id, order_id, store_id, cashier_id, subtotal, tax_amount,"
            + " discount_amount, total, currency, tax_exempt, exempt_reason,"
            + " transaction_ts, created_at"
            + " FROM pos_log_entries WHERE tenant_id=? ORDER BY transaction_ts DESC LIMIT 200",
        ps -> ps.setObject(1, tenantId),
        this::mapPosLogEntry,
        "list pos log");
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
