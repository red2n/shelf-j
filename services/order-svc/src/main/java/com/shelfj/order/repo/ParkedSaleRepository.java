package com.shelfj.order.repo;

import com.shelfj.ids.Ids;
import com.shelfj.order.dto.Dtos.NoSaleResponse;
import com.shelfj.order.dto.Dtos.ParkedSaleItemResponse;
import com.shelfj.order.dto.Dtos.ParkedSaleResponse;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistence for parked sales and no-sale log. */
@ApplicationScoped
public class ParkedSaleRepository extends BaseOutboxRepository {

  /**
   * Stores a parked sale with its lines — atomically.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale's id, already a UUIDv7
   * @param cashierId the cashier parking it
   * @param storeId the store the sale was rung in
   * @param customerId the customer, or {@code null} for an anonymous sale
   * @param customerName a name to show on the parked list, or {@code null}
   * @param subtotal the basket total after line discounts
   * @param discountTotal the discount taken off across all lines
   * @param notes free-text note, or {@code null}
   * @param items the lines being parked
   * @return the parked sale as stored
   */
  public ParkedSaleResponse park(
      UUID tenantId,
      UUID saleId,
      UUID cashierId,
      UUID storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      String notes,
      List<ParkedSaleItemResponse> items) {
    return inTx(
        c -> {
          insertParkedSale(
              c,
              tenantId,
              saleId,
              cashierId,
              storeId,
              customerId,
              customerName,
              subtotal,
              discountAmount,
              notes);
          for (var item : items) {
            insertParkedItem(c, tenantId, saleId, item);
          }
          return buildResponse(tenantId, saleId, c);
        },
        "park sale");
  }

  /**
   * Reads one parked sale with its lines.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param saleId the parked sale to read
   * @return the parked sale
   * @throws com.shelfj.web.ApiException a 404 when no such parked sale exists in this tenant
   */
  public ParkedSaleResponse findById(UUID tenantId, UUID saleId) {
    return inTx(c -> buildResponse(tenantId, saleId, c), "find parked sale");
  }

  /**
   * The still-open parked sales, newest first.
   *
   * <p>Cancelled and resumed sales are excluded: this backs the till's "pick one back up" list.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId restrict to one store, or {@code null} for the whole tenant
   * @return the open parked sales with their lines
   */
  public List<ParkedSaleResponse> listOpen(UUID tenantId, UUID storeId) {
    return inTx(
        c -> {
          String sql =
              storeId == null
                  ? "SELECT id FROM parked_sales WHERE tenant_id=? AND resumed_at IS NULL ORDER BY parked_at DESC"
                  : "SELECT id FROM parked_sales WHERE tenant_id=? AND store_id=? AND resumed_at IS NULL ORDER BY parked_at DESC";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (storeId != null) ps.setObject(2, storeId);
            List<ParkedSaleResponse> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                results.add(buildResponse(tenantId, rs.getObject("id", UUID.class), c));
              }
            }
            return results;
          }
        },
        "list parked sales");
  }

  /**
   * Marks a parked sale cancelled so it leaves the open list.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param saleId the parked sale to cancel
   */
  public void cancel(UUID tenantId, UUID saleId) {
    exec(
        "DELETE FROM parked_sales WHERE tenant_id=? AND id=? AND resumed_at IS NULL",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, saleId);
        },
        "cancel parked sale");
  }

  /**
   * Appends a no-sale / open-drawer audit entry.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose drawer was opened, or {@code null}
   * @param cashierId the cashier who opened it
   * @param tillSessionId the till session it happened in, or {@code null}
   * @param reason the stated reason
   * @param orderId an associated order, or {@code null} — a no-sale normally has none
   * @return the logged entry
   */
  public NoSaleResponse logNoSale(
      UUID tenantId,
      UUID storeId,
      UUID cashierId,
      UUID tillSessionId,
      String reason,
      UUID authorisedBy) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    exec(
        "INSERT INTO pos_no_sale_log (id, tenant_id, store_id, cashier_id, till_session_id,"
            + " reason, authorised_by, logged_at) VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, storeId);
          ps.setObject(4, cashierId);
          ps.setObject(5, tillSessionId);
          ps.setString(6, reason);
          ps.setObject(7, authorisedBy);
          ps.setObject(8, now.atOffset(ZoneOffset.UTC));
        },
        "log no-sale");
    return new NoSaleResponse(
        id.toString(), storeId == null ? null : storeId.toString(), reason, now.toString());
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ─────────────────────────────────────────── private helpers

  private void insertParkedSale(
      Connection c,
      UUID tenantId,
      UUID saleId,
      UUID cashierId,
      UUID storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      String notes)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO parked_sales (id, tenant_id, store_id, cashier_id, customer_id,"
                + " customer_name, subtotal, discount_amount, notes, parked_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, saleId);
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, cashierId);
      ps.setObject(5, customerId == null ? null : UUID.fromString(customerId));
      ps.setString(6, customerName);
      ps.setBigDecimal(7, subtotal);
      ps.setBigDecimal(8, discountAmount);
      ps.setString(9, notes);
      ps.setObject(10, Instant.now().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertParkedItem(
      Connection c, UUID tenantId, UUID saleId, ParkedSaleItemResponse item) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO parked_sale_items (id, tenant_id, sale_id, variant_id, qty,"
                + " unit_price, line_total, discount_amount, notes, markdown_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, saleId);
      ps.setObject(4, UUID.fromString(item.variantId()));
      ps.setBigDecimal(5, item.qty());
      ps.setBigDecimal(6, item.unitPrice());
      ps.setBigDecimal(7, item.lineTotal());
      ps.setBigDecimal(8, item.discountAmount() == null ? BigDecimal.ZERO : item.discountAmount());
      ps.setString(9, item.notes());
      ps.setObject(
          10, item.markdownId() == null ? null : Parsing.uuid(item.markdownId(), "markdownId"));
      ps.executeUpdate();
    }
  }

  private ParkedSaleResponse buildResponse(UUID tenantId, UUID saleId, Connection c)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, cashier_id, customer_id, customer_name,"
                + " subtotal, discount_amount, notes, parked_at, expires_at, resumed_at, order_id"
                + " FROM parked_sales WHERE tenant_id=? AND id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound("PARKED_SALE_NOT_FOUND", "Parked sale not found");
        UUID storeId = rs.getObject("store_id", UUID.class);
        UUID custId = rs.getObject("customer_id", UUID.class);
        String parkedAt = rs.getObject("parked_at", OffsetDateTime.class).toInstant().toString();
        OffsetDateTime expiresOdt = rs.getObject("expires_at", OffsetDateTime.class);
        String expiresAt = expiresOdt == null ? null : expiresOdt.toInstant().toString();
        List<ParkedSaleItemResponse> items = fetchItems(c, tenantId, saleId);
        return new ParkedSaleResponse(
            saleId.toString(),
            storeId == null ? null : storeId.toString(),
            custId == null ? null : custId.toString(),
            rs.getString("customer_name"),
            rs.getBigDecimal("subtotal"),
            rs.getBigDecimal("discount_amount"),
            items,
            rs.getString("notes"),
            parkedAt,
            expiresAt);
      }
    }
  }

  private List<ParkedSaleItemResponse> fetchItems(Connection c, UUID tenantId, UUID saleId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, sale_id, variant_id, qty, unit_price, line_total,"
                + " discount_amount, notes, markdown_id"
                + " FROM parked_sale_items WHERE tenant_id=? AND sale_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleId);
      List<ParkedSaleItemResponse> list = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          list.add(
              new ParkedSaleItemResponse(
                  rs.getObject("variant_id", UUID.class).toString(),
                  rs.getBigDecimal("qty"),
                  rs.getBigDecimal("unit_price"),
                  rs.getBigDecimal("discount_amount"),
                  rs.getBigDecimal("line_total"),
                  rs.getString("notes"),
                  rs.getObject("markdown_id", UUID.class) == null
                      ? null
                      : rs.getObject("markdown_id", UUID.class).toString()));
        }
      }
      return list;
    }
  }
}
