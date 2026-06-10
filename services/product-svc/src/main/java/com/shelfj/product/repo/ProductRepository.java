package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.ItemRevision;
import com.shelfj.product.domain.Domain.ItemTemplate;
import com.shelfj.product.domain.Domain.ItemTemplateApplication;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.UomClass;
import com.shelfj.product.domain.Domain.UomDefinition;
import com.shelfj.product.domain.Domain.UomItemConversion;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog persistence (JDBC). Every query filters tenant_id FIRST (golden rule #3). Writes that
 * emit an event do so via the outbox in the same transaction (golden rule #6).
 */
@ApplicationScoped
public class ProductRepository extends BaseOutboxRepository {

  // ─────────────────────────────────────────────────────────────── brands

  public Brand createBrand(UUID tenantId, String name) {
    Instant now = Instant.now();
    var b = new Brand(UUID.randomUUID(), tenantId, name, Brand.STATUS_ACTIVE, now, now);
    exec(
        "INSERT INTO brands (id, tenant_id, name, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, b.id());
          ps.setObject(2, b.tenantId());
          ps.setString(3, b.name());
          ps.setString(4, b.status());
          ps.setObject(5, now.atOffset(ZoneOffset.UTC));
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
        },
        "create brand");
    return b;
  }

  public Optional<Brand> findBrand(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, status, created_at, updated_at"
                + " FROM brands WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapBrand,
            "find brand")
        .stream()
        .findFirst();
  }

  public List<Brand> listBrands(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, status, created_at, updated_at"
            + " FROM brands WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapBrand,
        "list brands");
  }

  public Brand updateBrand(UUID tenantId, UUID id, String name) {
    Instant now = Instant.now();
    exec(
        "UPDATE brands SET name = ?, updated_at = ? WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "update brand");
    return findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  public Brand deactivateBrand(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE brands SET status = 'INACTIVE', updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate brand");
    return findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  // ─────────────────────────────────────────────────────────── categories

  public Category createCategory(UUID tenantId, UUID parentId, String name) {
    Instant now = Instant.now();
    var c =
        new Category(UUID.randomUUID(), tenantId, parentId, name, Category.STATUS_ACTIVE, now, now);
    if (parentId != null && findCategory(tenantId, parentId).isEmpty()) {
      throw ApiException.badRequest("PARENT_NOT_FOUND", "parentId not found in this tenant");
    }
    exec(
        "INSERT INTO categories (id, tenant_id, parent_id, name, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.parentId());
          ps.setString(4, c.name());
          ps.setString(5, c.status());
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create category");
    return c;
  }

  public Optional<Category> findCategory(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND id = ?",
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
        "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
            + " FROM categories WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapCategory,
        "list categories");
  }

  public Category updateCategory(UUID tenantId, UUID id, String name, UUID parentId) {
    Instant now = Instant.now();
    exec(
        "UPDATE categories SET name = ?, parent_id = ?, updated_at = ?"
            + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setObject(2, parentId);
          ps.setObject(3, now.atOffset(ZoneOffset.UTC));
          ps.setObject(4, tenantId);
          ps.setObject(5, id);
        },
        "update category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  public Category deactivateCategory(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE categories SET status = 'INACTIVE', updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  // ──────────────────────────────────────────── products (atomic with outbox)

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
                  "UPDATE products SET name=?, description=?, brand_id=?, category_id=?,"
                      + " status=?, sellable_online=?, sellable_pos=?, updated_at=?"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setString(1, p.name());
            ps.setString(2, p.description());
            ps.setObject(3, p.brandId());
            ps.setObject(4, p.categoryId());
            ps.setString(5, p.status());
            ps.setBoolean(6, p.sellableOnline());
            ps.setBoolean(7, p.sellablePos());
            ps.setObject(8, p.updatedAt().atOffset(ZoneOffset.UTC));
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

  /** Catalog list — ACTIVE only, optionally online-only, optionally filtered by category. */
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

  /** Admin list — all statuses, optionally filtered by category and/or status. */
  public List<Product> listProductsAdmin(UUID tenantId, UUID categoryId, String status, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at"
                + " FROM products WHERE tenant_id = ?");
    if (categoryId != null) sql.append(" AND category_id = ?");
    if (status != null) sql.append(" AND status = ?");
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
          if (status != null) {
            ps.setString(i, status);
            i++;
          }
          ps.setInt(i, limit);
        },
        ProductRepository::mapProduct,
        "list products admin");
  }

  // ─────────────────────────────────────────── variants (atomic with outbox)

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
          insertVariant(c, v);
          insertOutbox(c, event);
          return v;
        },
        "create variant");
  }

  public Optional<Variant> findVariant(UUID tenantId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, product_id, sku, barcode, manufacturer_pn, attributes, unit,"
                + " status, created_at, updated_at"
                + " FROM product_variants WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            ProductRepository::mapVariant,
            "find variant")
        .stream()
        .findFirst();
  }

  public List<Variant> listVariants(UUID tenantId, UUID productId) {
    return query(
        "SELECT id, tenant_id, product_id, sku, barcode, manufacturer_pn, attributes, unit,"
            + " status, created_at, updated_at"
            + " FROM product_variants"
            + " WHERE tenant_id = ? AND product_id = ? AND status = 'ACTIVE'"
            + " ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        ProductRepository::mapVariant,
        "list variants");
  }

  public Variant updateVariant(
      UUID tenantId,
      UUID variantId,
      String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit) {
    Instant now = Instant.now();
    exec(
        "UPDATE product_variants SET sku=?, barcode=?, manufacturer_pn=?, attributes=?, unit=?,"
            + " updated_at=? WHERE tenant_id=? AND id=? AND status='ACTIVE'",
        ps -> {
          ps.setString(1, sku);
          ps.setString(2, barcode);
          ps.setString(3, manufacturerPn);
          ps.setString(4, attributes);
          ps.setString(5, unit);
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, tenantId);
          ps.setObject(8, variantId);
        },
        "update variant");
    return findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  public Variant delistVariant(UUID tenantId, UUID variantId) {
    Instant now = Instant.now();
    exec(
        "UPDATE product_variants SET status='INACTIVE', updated_at=?"
            + " WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, variantId);
        },
        "delist variant");
    return findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "DUPLICATE", "A record with that unique value already exists", List.of(), e);
    return dbError(what, e);
  }

  // ─────────────────────────────────────────────────────── inserts / mappers

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
      ps.setObject(10, p.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(11, p.updatedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertVariant(Connection c, Variant v) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO product_variants"
                + " (id, tenant_id, product_id, sku, barcode, manufacturer_pn, attributes, unit,"
                + " status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, v.id());
      ps.setObject(2, v.tenantId());
      ps.setObject(3, v.productId());
      ps.setString(4, v.sku());
      ps.setString(5, v.barcode());
      ps.setString(6, v.manufacturerPn());
      ps.setString(7, v.attributes());
      ps.setString(8, v.unit());
      ps.setString(9, v.status());
      ps.setObject(10, v.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(11, v.updatedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  // ─────────────────────────────────────────────────────────────── UOM

  public List<UomClass> listUomClasses() {
    return query(
        "SELECT id, code, name FROM uom_classes ORDER BY name",
        ps -> {},
        rs ->
            new UomClass(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")),
        "list uom classes");
  }

  public List<UomDefinition> listUomDefinitions(String classCode) {
    if (classCode != null) {
      return query(
          "SELECT id, class_code, code, name FROM uom_definitions WHERE class_code = ? ORDER BY name",
          ps -> ps.setString(1, classCode),
          ProductRepository::mapUomDef,
          "list uom definitions by class");
    }
    return query(
        "SELECT id, class_code, code, name FROM uom_definitions ORDER BY class_code, name",
        ps -> {},
        ProductRepository::mapUomDef,
        "list all uom definitions");
  }

  public Optional<BigDecimal> findStandardConversionFactor(String fromUom, String toUom) {
    var list =
        query(
            "SELECT factor FROM uom_standard_conversions WHERE from_uom = ? AND to_uom = ?",
            ps -> {
              ps.setString(1, fromUom);
              ps.setString(2, toUom);
            },
            rs -> rs.getBigDecimal("factor"),
            "find std conversion");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public UomItemConversion upsertItemConversion(UomItemConversion c) {
    return inTx(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO uom_item_conversions"
                      + " (id, tenant_id, variant_id, from_uom, to_uom, factor)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, variant_id, from_uom, to_uom)"
                      + " DO UPDATE SET factor = EXCLUDED.factor"
                      + " RETURNING id, tenant_id, variant_id, from_uom, to_uom, factor")) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.variantId());
            ps.setString(4, c.fromUom());
            ps.setString(5, c.toUom());
            ps.setBigDecimal(6, c.factor());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapItemConversion(rs);
            }
          }
        },
        "upsert item conversion");
  }

  public List<UomItemConversion> listItemConversions(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, from_uom, to_uom, factor"
            + " FROM uom_item_conversions WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY from_uom, to_uom",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapItemConversion,
        "list item conversions");
  }

  public boolean deleteItemConversion(UUID tenantId, UUID id) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement("DELETE FROM uom_item_conversions WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("delete item conversion", e);
    }
  }

  public Optional<BigDecimal> findItemConversionFactor(
      UUID tenantId, UUID variantId, String fromUom, String toUom) {
    var list =
        query(
            "SELECT factor FROM uom_item_conversions"
                + " WHERE tenant_id = ? AND variant_id = ? AND from_uom = ? AND to_uom = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setString(3, fromUom);
              ps.setString(4, toUom);
            },
            rs -> rs.getBigDecimal("factor"),
            "find item conversion factor");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private static UomDefinition mapUomDef(ResultSet rs) throws SQLException {
    return new UomDefinition(
        rs.getObject("id", UUID.class),
        rs.getString("class_code"),
        rs.getString("code"),
        rs.getString("name"));
  }

  private static UomItemConversion mapItemConversion(ResultSet rs) throws SQLException {
    return new UomItemConversion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("from_uom"),
        rs.getString("to_uom"),
        rs.getBigDecimal("factor"));
  }

  private static Brand mapBrand(ResultSet rs) throws SQLException {
    return new Brand(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Category mapCategory(ResultSet rs) throws SQLException {
    return new Category(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ─────────────────────────────────────────────────────── item revisions (Gap #12)

  public ItemRevision createRevisionWithOutbox(ItemRevision rev, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE item_revisions SET status='SUPERSEDED'"
                      + " WHERE tenant_id=? AND variant_id=? AND status='ACTIVE'"
                      + " AND effective_date <= ?")) {
            ps.setObject(1, rev.tenantId());
            ps.setObject(2, rev.variantId());
            ps.setObject(3, Date.valueOf(rev.effectiveDate()));
            ps.executeUpdate();
          }
          String sql =
              "INSERT INTO item_revisions"
                  + " (id, tenant_id, variant_id, revision, description, effective_date, status)"
                  + " VALUES (?,?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, rev.id());
            ps.setObject(2, rev.tenantId());
            ps.setObject(3, rev.variantId());
            ps.setString(4, rev.revision());
            ps.setString(5, rev.description());
            ps.setObject(6, Date.valueOf(rev.effectiveDate()));
            ps.setString(7, rev.status());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "REVISION_EXISTS", "Revision already exists", List.of(), null);
              ItemRevision saved = mapRevision(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create item revision");
  }

  public List<ItemRevision> listRevisions(UUID tenantId, UUID variantId) {
    return query(
        "SELECT * FROM item_revisions WHERE tenant_id=? AND variant_id=?"
            + " ORDER BY effective_date DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapRevision,
        "list item revisions");
  }

  public Optional<ItemRevision> currentRevision(UUID tenantId, UUID variantId) {
    var rows =
        query(
            "SELECT * FROM item_revisions WHERE tenant_id=? AND variant_id=?"
                + " AND effective_date <= CURRENT_DATE"
                + " ORDER BY effective_date DESC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            ProductRepository::mapRevision,
            "current item revision");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public Optional<ItemRevision> findRevision(UUID tenantId, UUID revisionId) {
    var rows =
        query(
            "SELECT * FROM item_revisions WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, revisionId);
            },
            ProductRepository::mapRevision,
            "find item revision");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static ItemRevision mapRevision(ResultSet rs) throws SQLException {
    return new ItemRevision(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("revision"),
        rs.getString("description"),
        rs.getDate("effective_date").toLocalDate(),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
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
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Variant mapVariant(ResultSet rs) throws SQLException {
    return new Variant(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("sku"),
        rs.getString("barcode"),
        rs.getString("manufacturer_pn"),
        rs.getString("attributes"),
        rs.getString("unit"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  public ItemTemplate createTemplate(ItemTemplate t, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO item_templates (id, tenant_id, name, description, attributes, status)"
                  + " VALUES (?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setString(3, t.name());
            ps.setString(4, t.description());
            ps.setString(5, t.attributes());
            ps.setString(6, t.status());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "TEMPLATE_EXISTS", "Template name already exists", List.of(), null);
              ItemTemplate saved = mapTemplate(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create item template");
  }

  public Optional<ItemTemplate> findTemplate(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT * FROM item_templates WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapTemplate,
            "find item template");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<ItemTemplate> listTemplates(UUID tenantId) {
    return query(
        "SELECT * FROM item_templates WHERE tenant_id=? AND status='ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapTemplate,
        "list item templates");
  }

  public ItemTemplate deactivateTemplate(UUID tenantId, UUID id) {
    exec(
        "UPDATE item_templates SET status='INACTIVE' WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        "deactivate item template");
    return findTemplate(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
  }

  public ItemTemplateApplication applyTemplate(
      UUID tenantId, UUID variantId, UUID templateId, OutboxRow event) {
    return inTx(
        c -> {
          ItemTemplate tpl =
              findTemplate(tenantId, templateId)
                  .orElseThrow(
                      () -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
          // Copy attributes onto the variant (only when template has attributes)
          if (tpl.attributes() != null && !tpl.attributes().isBlank()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE product_variants SET attributes=? WHERE tenant_id=? AND id=?")) {
              ps.setString(1, tpl.attributes());
              ps.setObject(2, tenantId);
              ps.setObject(3, variantId);
              ps.executeUpdate();
            }
          }
          UUID appId = UUID.randomUUID();
          String insertSql =
              "INSERT INTO item_template_applications"
                  + " (id, tenant_id, variant_id, template_id) VALUES (?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setObject(1, appId);
            ps.setObject(2, tenantId);
            ps.setObject(3, variantId);
            ps.setObject(4, templateId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(500, "DB_ERROR", "apply template failed", List.of(), null);
              ItemTemplateApplication app = mapApplication(rs);
              insertOutbox(c, event);
              return app;
            }
          }
        },
        "apply item template");
  }

  private static ItemTemplate mapTemplate(ResultSet rs) throws SQLException {
    return new ItemTemplate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("attributes"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ItemTemplateApplication mapApplication(ResultSet rs) throws SQLException {
    return new ItemTemplateApplication(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getObject("applied_at", OffsetDateTime.class).toInstant());
  }
}
