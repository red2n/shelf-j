package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog persistence (JDBC). Every query filters tenant_id FIRST (golden rule #3). Writes that
 * emit an event do so via the outbox in the same transaction (golden rule #6).
 */
@ApplicationScoped
public class ProductRepository extends BaseOutboxRepository {

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
    return query(
            "SELECT id, tenant_id, parent_id, name, created_at FROM categories WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapCategory,
            "find category")
        .stream()
        .findFirst();
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
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE products SET name=?, description=?, brand_id=?, category_id=?, status=?,"
                      + " sellable_online=?, sellable_pos=?, updated_at=?"
                      + " WHERE tenant_id=? AND id=?")) {
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
            if (ps.executeUpdate() == 0)
              throw ApiException.notFound("PRODUCT_NOT_FOUND", "No such product in this tenant");
          }
          insertOutbox(c, event);
          return p;
        },
        "update product");
  }

  public Optional<Product> findProduct(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at"
                + " FROM products WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapProduct,
            "find product")
        .stream()
        .findFirst();
  }

  /** List active products for a tenant, optionally filtered by category, newest first. */
  public List<Product> listProducts(UUID tenantId, UUID categoryId, boolean onlineOnly, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at"
                + " FROM products WHERE tenant_id = ? AND status = 'ACTIVE'");
    if (categoryId != null) sql.append(" AND category_id = ?");
    if (onlineOnly) sql.append(" AND sellable_online = true");
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i, tenantId);
          i++;
          if (categoryId != null) {
            ps.setObject(i, categoryId);
            i++;
          }
          ps.setInt(i, limit);
        },
        ProductRepository::mapProduct,
        "list products");
  }

  // --- variants (atomic with outbox) ---
  public Variant createVariantWithOutbox(Variant v, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT 1 FROM products WHERE tenant_id=? AND id=?")) {
            ps.setObject(1, v.tenantId());
            ps.setObject(2, v.productId());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("PRODUCT_NOT_FOUND", "Parent product not found");
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO product_variants"
                      + " (id, tenant_id, product_id, sku, barcode, attributes, unit, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
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
        "SELECT id, tenant_id, product_id, sku, barcode, attributes, unit, created_at"
            + " FROM product_variants WHERE tenant_id = ? AND product_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        ProductRepository::mapVariant,
        "list variants");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "DUPLICATE", "A record with that unique value already exists", List.of(), e);
    return dbError(what, e);
  }

  // --- inserts / mappers ---

  private void insertProduct(Connection c, Product p) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO products"
                + " (id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
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
}
