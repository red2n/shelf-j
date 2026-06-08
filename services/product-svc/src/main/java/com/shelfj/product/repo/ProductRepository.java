package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.service.OutboxStore;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Catalog persistence (JDBC). Every query filters tenant_id FIRST (golden rule #3). Writes that
 * emit an event do so via the outbox in the same transaction (golden rule #6).
 */
@ApplicationScoped
public class ProductRepository implements OutboxStore {

  @Inject DataSource dataSource;

  public record OutboxRow(
      String eventType, String topic, UUID tenantId, UUID aggregateId, String payload) {}

  // --- brands ---
  public Brand createBrand(UUID tenantId, String name) {
    var b = new Brand(UUID.randomUUID(), tenantId, name, Instant.now());
    exec(
        "INSERT INTO brands (id, tenant_id, name, created_at) VALUES (?,?,?,?)",
        ps -> {
          ps.setObject(1, b.id());
          ps.setObject(2, b.tenantId());
          ps.setString(3, b.name());
          ps.setTimestamp(4, Timestamp.from(b.createdAt()));
        },
        "create brand");
    return b;
  }

  public List<Brand> listBrands(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, created_at FROM brands WHERE tenant_id = ? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapBrand,
        "list brands");
  }

  // --- categories ---
  public Category createCategory(UUID tenantId, UUID parentId, String name) {
    var c = new Category(UUID.randomUUID(), tenantId, parentId, name, Instant.now());
    // validate parent belongs to tenant (if provided)
    if (parentId != null && findCategory(tenantId, parentId).isEmpty()) {
      throw ApiException.badRequest("PARENT_NOT_FOUND", "parentId not found in this tenant");
    }
    exec(
        "INSERT INTO categories (id, tenant_id, parent_id, name, created_at) VALUES (?,?,?,?,?)",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.parentId());
          ps.setString(4, c.name());
          ps.setTimestamp(5, Timestamp.from(c.createdAt()));
        },
        "create category");
    return c;
  }

  public Optional<Category> findCategory(UUID tenantId, UUID id) {
    var list =
        query(
            "SELECT id, tenant_id, parent_id, name, created_at FROM categories WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapCategory,
            "find category");
    return list.stream().findFirst();
  }

  public List<Category> listCategories(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, parent_id, name, created_at FROM categories WHERE tenant_id = ? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapCategory,
        "list categories");
  }

  // --- products (atomic with outbox) ---
  public Product createProductWithOutbox(Product p, OutboxRow event) {
    return inTx(
        c -> {
          insertProduct(c, p);
          insertOutbox(c, event);
          return p;
        },
        "create product");
  }

  public Product updateProductWithOutbox(Product p, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE products SET name=?, description=?, brand_id=?, category_id=?, status=?, "
                  + "sellable_online=?, sellable_pos=?, updated_at=? WHERE tenant_id=? AND id=?";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p.name());
            ps.setString(2, p.description());
            ps.setObject(3, p.brandId());
            ps.setObject(4, p.categoryId());
            ps.setString(5, p.status());
            ps.setBoolean(6, p.sellableOnline());
            ps.setBoolean(7, p.sellablePos());
            ps.setTimestamp(8, Timestamp.from(p.updatedAt()));
            ps.setObject(9, p.tenantId());
            ps.setObject(10, p.id());
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound("PRODUCT_NOT_FOUND", "No such product in this tenant");
            }
          }
          insertOutbox(c, event);
          return p;
        },
        "update product");
  }

  public Optional<Product> findProduct(UUID tenantId, UUID id) {
    var list =
        query(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status, sellable_online, sellable_pos, created_at, updated_at FROM products WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapProduct,
            "find product");
    return list.stream().findFirst();
  }

  /**
   * List active products for a tenant, optionally filtered by category, newest first, with a simple
   * limit.
   */
  public List<Product> listProducts(UUID tenantId, UUID categoryId, boolean onlineOnly, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status, sellable_online, sellable_pos, created_at, updated_at FROM products WHERE tenant_id = ? AND status = 'ACTIVE'");
    if (categoryId != null) sql.append(" AND category_id = ?");
    if (onlineOnly) sql.append(" AND sellable_online = true");
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (categoryId != null) ps.setObject(i++, categoryId);
          ps.setInt(i, limit);
        },
        ProductRepository::mapProduct,
        "list products");
  }

  // --- variants (atomic with outbox) ---
  public Variant createVariantWithOutbox(Variant v, OutboxRow event) {
    return inTx(
        c -> {
          // ensure parent product exists in tenant
          try (PreparedStatement ps =
              c.prepareStatement("SELECT 1 FROM products WHERE tenant_id=? AND id=?")) {
            ps.setObject(1, v.tenantId());
            ps.setObject(2, v.productId());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("PRODUCT_NOT_FOUND", "Parent product not found");
            }
          }
          String sql =
              "INSERT INTO product_variants (id, tenant_id, product_id, sku, barcode, attributes, unit, created_at) "
                  + "VALUES (?,?,?,?,?,?,?,?)";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, v.id());
            ps.setObject(2, v.tenantId());
            ps.setObject(3, v.productId());
            ps.setString(4, v.sku());
            ps.setString(5, v.barcode());
            ps.setString(6, v.attributes());
            ps.setString(7, v.unit());
            ps.setTimestamp(8, Timestamp.from(v.createdAt()));
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return v;
        },
        "create variant");
  }

  public List<Variant> listVariants(UUID tenantId, UUID productId) {
    return query(
        "SELECT id, tenant_id, product_id, sku, barcode, attributes, unit, created_at FROM product_variants WHERE tenant_id = ? AND product_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        ProductRepository::mapVariant,
        "list variants");
  }

  // --- outbox drain (OutboxStore) ---
  @Override
  public List<PendingOutbox> pendingOutbox(int limit) {
    return query(
        "SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new PendingOutbox(
                rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload")),
        "read outbox");
  }

  @Override
  public void markPublished(UUID id) {
    exec(
        "UPDATE outbox SET published_at = now() WHERE id = ?",
        ps -> ps.setObject(1, id),
        "mark outbox published");
  }

  // --- inserts/helpers ---

  private void insertProduct(Connection c, Product p) throws SQLException {
    String sql =
        "INSERT INTO products (id, tenant_id, name, description, brand_id, category_id, status, "
            + "sellable_online, sellable_pos, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, p.id());
      ps.setObject(2, p.tenantId());
      ps.setString(3, p.name());
      ps.setString(4, p.description());
      ps.setObject(5, p.brandId());
      ps.setObject(6, p.categoryId());
      ps.setString(7, p.status());
      ps.setBoolean(8, p.sellableOnline());
      ps.setBoolean(9, p.sellablePos());
      ps.setTimestamp(10, Timestamp.from(p.createdAt()));
      ps.setTimestamp(11, Timestamp.from(p.updatedAt()));
      ps.executeUpdate();
    }
  }

  private void insertOutbox(Connection c, OutboxRow o) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload) VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, o.eventType());
      ps.setString(3, o.topic());
      ps.setObject(4, o.tenantId());
      ps.setObject(5, o.aggregateId());
      ps.setString(6, o.payload());
      ps.executeUpdate();
    }
  }

  // tx + small JDBC helpers
  private interface TxWork<R> {
    R run(Connection c) throws SQLException;
  }

  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private static final String UNIQUE_VIOLATION = "23505";

  private <R> R inTx(TxWork<R> work, String what) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        R r = work.run(c);
        c.commit();
        return r;
      } catch (SQLException e) {
        c.rollback();
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
          throw new ApiException(
              409, "DUPLICATE", "A record with that unique value already exists", List.of(), e);
        }
        throw dbError(what, e);
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw dbError(what + " (connection)", e);
    }
  }

  private void exec(String sql, Binder binder, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      ps.executeUpdate();
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState()))
        throw new ApiException(409, "DUPLICATE", "Already exists", List.of(), e);
      throw dbError(what, e);
    }
  }

  private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) out.add(mapper.map(rs));
        return out;
      }
    } catch (SQLException e) {
      throw dbError(what, e);
    }
  }

  private static Brand mapBrand(ResultSet rs) throws SQLException {
    return new Brand(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Category mapCategory(ResultSet rs) throws SQLException {
    return new Category(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_id", UUID.class),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Product mapProduct(ResultSet rs) throws SQLException {
    return new Product(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getObject("brand_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getString("status"),
        rs.getBoolean("sellable_online"),
        rs.getBoolean("sellable_pos"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Variant mapVariant(ResultSet rs) throws SQLException {
    return new Variant(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("sku"),
        rs.getString("barcode"),
        rs.getString("attributes"),
        rs.getString("unit"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
