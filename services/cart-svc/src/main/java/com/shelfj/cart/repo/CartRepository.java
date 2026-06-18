package com.shelfj.cart.repo;

import com.shelfj.cart.domain.Domain.Cart;
import com.shelfj.cart.domain.Domain.CartItem;
import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link Cart} and {@link CartItem}. All queries filter tenant_id first. */
@ApplicationScoped
public class CartRepository extends BaseJdbcRepository {

  // ── Carts ─────────────────────────────────────────────────────────────────

  public Cart insert(Cart c) {
    exec(
        "INSERT INTO carts (id, tenant_id, customer_id, session_id, store_id, status,"
            + " created_at, updated_at) VALUES (?,?,?,?,?,?, now(), now())",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.customerId());
          ps.setString(4, c.sessionId());
          ps.setObject(5, c.storeId());
          ps.setString(6, c.status());
        },
        "insert cart");
    return findById(c.tenantId(), c.id()).orElseThrow();
  }

  public Optional<Cart> findById(UUID tenantId, UUID cartId) {
    return query(
            "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                + " created_at, updated_at FROM carts WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, cartId);
            },
            this::mapCart,
            "find cart by id")
        .stream()
        .findFirst();
  }

  public Optional<Cart> findActiveByCustomer(UUID tenantId, UUID customerId) {
    return query(
            "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                + " created_at, updated_at FROM carts"
                + " WHERE tenant_id = ? AND customer_id = ? AND status = 'ACTIVE' LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            this::mapCart,
            "find active cart by customer")
        .stream()
        .findFirst();
  }

  public Optional<Cart> findActiveBySession(UUID tenantId, String sessionId) {
    return query(
            "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                + " created_at, updated_at FROM carts"
                + " WHERE tenant_id = ? AND session_id = ? AND status = 'ACTIVE' LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, sessionId);
            },
            this::mapCart,
            "find active cart by session")
        .stream()
        .findFirst();
  }

  /** Marks a cart's status and touches updated_at. */
  public void updateStatus(UUID tenantId, UUID cartId, String status) {
    exec(
        "UPDATE carts SET status = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, tenantId);
          ps.setObject(3, cartId);
        },
        "update cart status");
  }

  /**
   * Marks the customer's ACTIVE cart at the given store as CHECKED_OUT. Called when an OrderPlaced
   * event arrives for a known customer. No-op if no matching cart exists.
   */
  public void markCheckedOutByCustomerAndStore(UUID tenantId, UUID customerId, UUID storeId) {
    exec(
        "UPDATE carts SET status = 'CHECKED_OUT', updated_at = now()"
            + " WHERE tenant_id = ? AND customer_id = ? AND store_id = ? AND status = 'ACTIVE'",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setObject(3, storeId);
        },
        "mark cart checked out");
  }

  // ── Items ─────────────────────────────────────────────────────────────────

  /**
   * Upserts an item: if the variant is already in the cart, qty is incremented and unit_price is
   * updated if the new value is non-null.
   */
  public CartItem upsertItem(CartItem item) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO cart_items (id, cart_id, tenant_id, variant_id, qty, unit_price,"
                      + " added_at) VALUES (?,?,?,?,?,?, now())"
                      + " ON CONFLICT (cart_id, variant_id) DO UPDATE SET"
                      + "   qty        = cart_items.qty + EXCLUDED.qty,"
                      + "   unit_price = COALESCE(EXCLUDED.unit_price, cart_items.unit_price)"
                      + " RETURNING id, cart_id, tenant_id, variant_id, qty, unit_price, added_at")) {
            ps.setObject(1, item.id());
            ps.setObject(2, item.cartId());
            ps.setObject(3, item.tenantId());
            ps.setObject(4, item.variantId());
            ps.setBigDecimal(5, item.qty());
            ps.setBigDecimal(6, item.unitPrice());
            try (var rs = ps.executeQuery()) {
              if (rs.next()) return mapItem(rs);
            }
          }
          throw ApiException.unprocessable("CART_ITEM_UPSERT_FAILED", "upsert returned no row");
        },
        "upsert cart item");
  }

  public Optional<CartItem> findItemById(UUID tenantId, UUID itemId) {
    return query(
            "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price, added_at"
                + " FROM cart_items WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, itemId);
            },
            this::mapItem,
            "find cart item")
        .stream()
        .findFirst();
  }

  public List<CartItem> findItemsByCart(UUID tenantId, UUID cartId) {
    return query(
        "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price, added_at"
            + " FROM cart_items WHERE tenant_id = ? AND cart_id = ? ORDER BY added_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, cartId);
        },
        this::mapItem,
        "list cart items");
  }

  public void updateItemQty(UUID tenantId, UUID cartId, UUID itemId, BigDecimal qty) {
    exec(
        "UPDATE cart_items SET qty = ? WHERE tenant_id = ? AND cart_id = ? AND id = ?",
        ps -> {
          ps.setBigDecimal(1, qty);
          ps.setObject(2, tenantId);
          ps.setObject(3, cartId);
          ps.setObject(4, itemId);
        },
        "update cart item qty");
  }

  public void deleteItem(UUID tenantId, UUID cartId, UUID itemId) {
    exec(
        "DELETE FROM cart_items WHERE tenant_id = ? AND cart_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, cartId);
          ps.setObject(3, itemId);
        },
        "delete cart item");
  }

  // ── Merge ─────────────────────────────────────────────────────────────────

  /**
   * Merges all items from the guest cart into the target cart within one transaction. Items already
   * present have their qty incremented. The guest cart is then marked ABANDONED.
   */
  public void mergeItems(UUID tenantId, UUID guestCartId, UUID targetCartId) {
    inTx(
        c -> {
          // Upsert each guest item into the target cart.
          try (var sel =
              c.prepareStatement(
                  "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price FROM cart_items"
                      + " WHERE tenant_id = ? AND cart_id = ?")) {
            sel.setObject(1, tenantId);
            sel.setObject(2, guestCartId);
            try (var rs = sel.executeQuery()) {
              while (rs.next()) {
                try (var ins =
                    c.prepareStatement(
                        "INSERT INTO cart_items (id, cart_id, tenant_id, variant_id, qty,"
                            + " unit_price, added_at) VALUES (gen_random_uuid(),?,?,?,?,?,now())"
                            + " ON CONFLICT (cart_id, variant_id) DO UPDATE SET"
                            + "   qty = cart_items.qty + EXCLUDED.qty,"
                            + "   unit_price = COALESCE(EXCLUDED.unit_price, cart_items.unit_price)")) {
                  ins.setObject(1, targetCartId);
                  ins.setObject(2, tenantId);
                  ins.setObject(3, rs.getObject("variant_id", UUID.class));
                  ins.setBigDecimal(4, rs.getBigDecimal("qty"));
                  ins.setBigDecimal(5, rs.getBigDecimal("unit_price"));
                  ins.executeUpdate();
                }
              }
            }
          }
          // Abandon the guest cart.
          try (var upd =
              c.prepareStatement(
                  "UPDATE carts SET status = 'ABANDONED', updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            upd.setObject(1, tenantId);
            upd.setObject(2, guestCartId);
            upd.executeUpdate();
          }
          return null;
        },
        "merge guest cart");
  }

  // ── Mappers ───────────────────────────────────────────────────────────────

  private Cart mapCart(ResultSet rs) throws SQLException {
    return new Cart(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("session_id"),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private CartItem mapItem(ResultSet rs) throws SQLException {
    return new CartItem(
        rs.getObject("id", UUID.class),
        rs.getObject("cart_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_price"),
        rs.getObject("added_at", OffsetDateTime.class).toInstant());
  }
}
