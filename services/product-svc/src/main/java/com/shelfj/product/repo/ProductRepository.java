package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.CatalogGroup;
import com.shelfj.product.domain.Domain.CatalogGroupElement;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.CategorySet;
import com.shelfj.product.domain.Domain.CategorySetMember;
import com.shelfj.product.domain.Domain.ContainerType;
import com.shelfj.product.domain.Domain.ItemAttributeGroup;
import com.shelfj.product.domain.Domain.ItemAttributeGroupField;
import com.shelfj.product.domain.Domain.ItemCrossReference;
import com.shelfj.product.domain.Domain.ItemRelationship;
import com.shelfj.product.domain.Domain.ItemRevision;
import com.shelfj.product.domain.Domain.ItemTemplate;
import com.shelfj.product.domain.Domain.ItemTemplateApplication;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.UomClass;
import com.shelfj.product.domain.Domain.UomDefinition;
import com.shelfj.product.domain.Domain.UomItemConversion;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.domain.Domain.VariantAttributeGroupValues;
import com.shelfj.product.domain.Domain.VariantCatalogAssignment;
import com.shelfj.product.domain.Domain.VariantCategorySetAssignment;
import com.shelfj.product.domain.Domain.VariantContainerLink;
import com.shelfj.product.domain.Domain.VariantWithProduct;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 *
 * <p>{@link #findProduct} is the single highest-traffic read (storefront product page, POS lookup)
 * and is cached in Redis, cache-aside, with active invalidation on the one write path that mutates
 * a product row ({@link #updateProductWithOutbox}). Only positive lookups are cached, so a freshly
 * created product needs no cache priming or invalidation.
 */
@ApplicationScoped
public class ProductRepository extends BaseOutboxRepository {

  /**
   * Field separator for the flat cache encoding — Postgres TEXT columns can never contain a NUL
   * byte, so this never collides with real content and needs no escaping.
   */
  private static final String FS = "\u0000";

  /**
   * Products change far less often than they're read; a longer TTL than cart's is safe because
   * every actual write path goes through {@link #updateProductWithOutbox}, which evicts.
   */
  private static final long PRODUCT_TTL_SECONDS = 300;

  @Inject RedisCommands<String, String> redis;

  private static String productKey(UUID tenantId, UUID id) {
    return "product:" + tenantId + ":" + id;
  }

  private static String encodeProduct(Product p) {
    return String.join(
        FS,
        p.id().toString(),
        p.tenantId().toString(),
        p.name(),
        p.description() == null ? "" : p.description(),
        p.brandId() == null ? "" : p.brandId().toString(),
        p.categoryId() == null ? "" : p.categoryId().toString(),
        p.status(),
        Boolean.toString(p.sellableOnline()),
        Boolean.toString(p.sellablePos()),
        p.createdAt().toString(),
        p.updatedAt().toString());
  }

  private static Product decodeProduct(String s) {
    String[] f = s.split(FS, -1);
    return new Product(
        UUID.fromString(f[0]),
        UUID.fromString(f[1]),
        f[2],
        f[3].isEmpty() ? null : f[3],
        f[4].isEmpty() ? null : UUID.fromString(f[4]),
        f[5].isEmpty() ? null : UUID.fromString(f[5]),
        f[6],
        Boolean.parseBoolean(f[7]),
        Boolean.parseBoolean(f[8]),
        Instant.parse(f[9]),
        Instant.parse(f[10]));
  }

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

  public Optional<Brand> findBrandByName(UUID tenantId, String name) {
    return query(
            "SELECT id, tenant_id, name, status, created_at, updated_at"
                + " FROM brands WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            ProductRepository::mapBrand,
            "find brand by name")
        .stream()
        .findFirst();
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

  public Optional<Category> findCategoryByName(UUID tenantId, String name) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            ProductRepository::mapCategory,
            "find category by name")
        .stream()
        .findFirst();
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
    Product updated =
        inTx(
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
                  throw ApiException.notFound(
                      "PRODUCT_NOT_FOUND", "No such product in this tenant");
              }
              insertOutbox(c, event);
              return p;
            },
            "update product");
    redis.del(productKey(p.tenantId(), p.id()));
    return updated;
  }

  public Optional<Product> findProduct(UUID tenantId, UUID id) {
    String cacheKey = productKey(tenantId, id);
    String cached = redis.get(cacheKey);
    if (cached != null) return Optional.of(decodeProduct(cached));
    Optional<Product> fresh =
        query(
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
    fresh.ifPresent(
        p -> redis.set(cacheKey, encodeProduct(p), SetArgs.Builder.ex(PRODUCT_TTL_SECONDS)));
    return fresh;
  }

  /**
   * Find an ACTIVE product by name within a category scope — used by bulk-import REPLACE to reuse
   * (rather than duplicate) an existing product. {@code categoryId} null matches uncategorised.
   */
  public Optional<Product> findProductByNameAndCategory(
      UUID tenantId, String name, UUID categoryId) {
    String sql =
        "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
            + " sellable_online, sellable_pos, created_at, updated_at"
            + " FROM products WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE' AND "
            + (categoryId == null ? "category_id IS NULL" : "category_id = ?");
    return query(
            sql,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
              if (categoryId != null) ps.setObject(3, categoryId);
            },
            ProductRepository::mapProduct,
            "find product by name")
        .stream()
        .findFirst();
  }

  /** Delete a variant by its (tenant, SKU) — used by bulk-import REPLACE to upsert by SKU. */
  public void deleteVariantBySku(UUID tenantId, String sku) {
    exec(
        "DELETE FROM product_variants WHERE tenant_id = ? AND sku = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, sku);
        },
        "delete variant by sku");
  }

  /** Catalog list — ACTIVE only, optionally online-only, optionally filtered by category. */
  public List<Product> listProducts(
      UUID tenantId,
      UUID categoryId,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at"
                + " FROM products WHERE tenant_id = ? AND status = 'ACTIVE'");
    if (categoryId != null) sql.append(" AND category_id = ?");
    if (onlineOnly) sql.append(" AND sellable_online = true");
    if (posOnly) sql.append(" AND sellable_pos = true");
    if (storeId != null) sql.append(STORE_ASSORTMENT_FILTER.replace("$P", "products.id"));
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (categoryId != null) {
            ps.setObject(i++, categoryId);
          }
          if (storeId != null) {
            ps.setObject(i++, storeId);
          }
          ps.setInt(i, limit);
        },
        ProductRepository::mapProduct,
        "list products");
  }

  /**
   * Assortment predicate: keep a product if it has NO store rows (sold everywhere) OR an explicit
   * row for this store. {@code $P} is the product-id column expression (e.g. {@code products.id} or
   * {@code p.id}). Binds exactly one {@code store_id} parameter.
   */
  private static final String STORE_ASSORTMENT_FILTER =
      " AND (NOT EXISTS (SELECT 1 FROM product_stores ps WHERE ps.product_id = $P)"
          + " OR EXISTS (SELECT 1 FROM product_stores ps WHERE ps.product_id = $P AND ps.store_id = ?))";

  /**
   * Admin list — all statuses, optionally filtered by category and/or status. Keyset-paginated on
   * {@code (created_at, id)}; previously had a {@code limit} param but no cursor, so a tenant with
   * more products than the page size could never see the rest.
   */
  public List<Product> listProductsAdmin(
      UUID tenantId,
      UUID categoryId,
      String status,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, description, brand_id, category_id, status,"
                + " sellable_online, sellable_pos, created_at, updated_at"
                + " FROM products WHERE tenant_id = ?");
    if (categoryId != null) sql.append(" AND category_id = ?");
    if (status != null) sql.append(" AND status = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) < (?, ?)");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
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
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i, afterCreatedAt.atOffset(ZoneOffset.UTC));
            i++;
            ps.setObject(i, afterId);
            i++;
          }
          ps.setInt(i, limit);
        },
        ProductRepository::mapProduct,
        "list products admin");
  }

  /**
   * Full-text / attribute search for the storefront and POS lookup. Supports name ILIKE (prefix
   * wildcard), exact SKU, and exact barcode. When sku or barcode is supplied a JOIN to
   * product_variants is performed — DISTINCT prevents duplicates when a product has several
   * matching variants.
   */
  public List<Product> searchProducts(
      UUID tenantId,
      String q,
      String sku,
      String barcode,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    boolean hasVariantFilter = sku != null || barcode != null;
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT p.id, p.tenant_id, p.name, p.description, p.brand_id,"
                + " p.category_id, p.status, p.sellable_online, p.sellable_pos,"
                + " p.created_at, p.updated_at FROM products p");
    if (hasVariantFilter) {
      sql.append(
          " JOIN product_variants v"
              + " ON v.product_id = p.id AND v.tenant_id = p.tenant_id AND v.status = 'ACTIVE'");
    }
    sql.append(" WHERE p.tenant_id = ? AND p.status = 'ACTIVE'");
    if (q != null) sql.append(" AND p.name ILIKE ?");
    if (sku != null) sql.append(" AND v.sku = ?");
    if (barcode != null) sql.append(" AND v.barcode = ?");
    if (onlineOnly) sql.append(" AND p.sellable_online = true");
    if (posOnly) sql.append(" AND p.sellable_pos = true");
    if (storeId != null) sql.append(STORE_ASSORTMENT_FILTER.replace("$P", "p.id"));
    sql.append(" ORDER BY p.created_at DESC LIMIT ?");
    String finalSql = sql.toString();
    return query(
        finalSql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (q != null) ps.setString(i++, "%" + escapeLike(q) + "%");
          if (sku != null) ps.setString(i++, sku);
          if (barcode != null) ps.setString(i++, barcode);
          if (storeId != null) ps.setObject(i++, storeId);
          ps.setInt(i, limit);
        },
        ProductRepository::mapProductAlias,
        "search products");
  }

  // ── per-store assortment ─────────────────────────────────────────────────

  public List<UUID> storesForProduct(UUID tenantId, UUID productId) {
    return query(
        "SELECT store_id FROM product_stores WHERE tenant_id = ? AND product_id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        rs -> rs.getObject("store_id", UUID.class),
        "stores for product");
  }

  /** Replace a product's store assortment. Empty list = sold at all stores (no rows). */
  public void setStoresForProduct(UUID tenantId, UUID productId, List<UUID> storeIds) {
    inTx(
        c -> {
          try (var del =
              c.prepareStatement(
                  "DELETE FROM product_stores WHERE tenant_id = ? AND product_id = ?")) {
            del.setObject(1, tenantId);
            del.setObject(2, productId);
            del.executeUpdate();
          }
          if (!storeIds.isEmpty()) {
            try (var ins =
                c.prepareStatement(
                    "INSERT INTO product_stores (tenant_id, product_id, store_id)"
                        + " VALUES (?, ?, ?)")) {
              for (UUID sid : storeIds) {
                ins.setObject(1, tenantId);
                ins.setObject(2, productId);
                ins.setObject(3, sid);
                ins.addBatch();
              }
              ins.executeBatch();
            }
          }
          return null;
        },
        "set product stores");
  }

  /** Adds store assignments without removing existing ones (idempotent — skips duplicates). */
  public void addStoreAssignments(UUID tenantId, UUID productId, java.util.List<UUID> storeIds) {
    if (storeIds == null || storeIds.isEmpty()) return;
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO product_stores (tenant_id, product_id, store_id)"
                      + " VALUES (?, ?, ?) ON CONFLICT (tenant_id, product_id, store_id) DO NOTHING")) {
            for (UUID sid : storeIds) {
              ps.setObject(1, tenantId);
              ps.setObject(2, productId);
              ps.setObject(3, sid);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "add store assignments");
  }

  /**
   * Escape LIKE metacharacters in user-supplied search text: a literal {@code %}/{@code _} must
   * match itself, not act as a wildcard the caller can use to force expensive full scans.
   */
  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Looks up a variant by barcode and returns it together with its parent product in one query. */
  public Optional<VariantWithProduct> findVariantByBarcode(UUID tenantId, String barcode) {
    return query(
            "SELECT v.id AS v_id, v.tenant_id AS v_tid, v.product_id, v.sku, v.barcode,"
                + " v.manufacturer_pn, v.attributes, v.unit, v.status AS v_status,"
                + " v.created_at AS v_cat, v.updated_at AS v_uat,"
                + " p.id AS p_id, p.name, p.description, p.brand_id, p.category_id,"
                + " p.status AS p_status, p.sellable_online, p.sellable_pos,"
                + " p.created_at AS p_cat, p.updated_at AS p_uat"
                + " FROM product_variants v"
                + " JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id"
                + " WHERE v.tenant_id = ? AND v.barcode = ?"
                + " AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, barcode);
            },
            ProductRepository::mapVariantWithProduct,
            "find variant by barcode")
        .stream()
        .findFirst();
  }

  /**
   * Resolves a batch of variant ids to their variant + parent product in one query. Unlike the
   * storefront barcode lookup this does NOT filter on {@code status='ACTIVE'} — admin screens need
   * to resolve names/SKUs for every variant they show, including inactive ones. tenant_id is
   * filtered first (golden rule #3).
   */
  public List<VariantWithProduct> findVariantsByIds(UUID tenantId, List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return query(
        "SELECT v.id AS v_id, v.tenant_id AS v_tid, v.product_id, v.sku, v.barcode,"
            + " v.manufacturer_pn, v.attributes, v.unit, v.status AS v_status,"
            + " v.created_at AS v_cat, v.updated_at AS v_uat,"
            + " p.id AS p_id, p.name, p.description, p.brand_id, p.category_id,"
            + " p.status AS p_status, p.sellable_online, p.sellable_pos,"
            + " p.created_at AS p_cat, p.updated_at AS p_uat"
            + " FROM product_variants v"
            + " JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id"
            + " WHERE v.tenant_id = ? AND v.id = ANY(?)",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        },
        ProductRepository::mapVariantWithProduct,
        "resolve variants by ids");
  }

  private static Product mapProductAlias(ResultSet rs) throws SQLException {
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

  private static VariantWithProduct mapVariantWithProduct(ResultSet rs) throws SQLException {
    Variant variant =
        new Variant(
            rs.getObject("v_id", UUID.class),
            rs.getObject("v_tid", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("sku"),
            rs.getString("barcode"),
            rs.getString("manufacturer_pn"),
            rs.getString("attributes"),
            rs.getString("unit"),
            rs.getString("v_status"),
            rs.getObject("v_cat", OffsetDateTime.class).toInstant(),
            rs.getObject("v_uat", OffsetDateTime.class).toInstant());
    Product product =
        new Product(
            rs.getObject("p_id", UUID.class),
            rs.getObject("v_tid", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getObject("brand_id", UUID.class),
            rs.getObject("category_id", UUID.class),
            rs.getString("p_status"),
            rs.getBoolean("sellable_online"),
            rs.getBoolean("sellable_pos"),
            rs.getObject("p_cat", OffsetDateTime.class).toInstant(),
            rs.getObject("p_uat", OffsetDateTime.class).toInstant());
    return new VariantWithProduct(variant, product);
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
        "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
            + " FROM item_revisions WHERE tenant_id=? AND variant_id=?"
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
            "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
                + " FROM item_revisions WHERE tenant_id=? AND variant_id=?"
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
            "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
                + " FROM item_revisions WHERE tenant_id=? AND id=?",
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

  // ── Supplier / Customer Cross-References (Gap #33) ──────────────────────

  public ItemCrossReference createCrossReference(ItemCrossReference x) {
    exec(
        "INSERT INTO item_cross_references"
            + " (id, tenant_id, variant_id, party_type, party_id, party_name,"
            + " cross_ref_number, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, x.id());
          ps.setObject(2, x.tenantId());
          ps.setObject(3, x.variantId());
          ps.setString(4, x.partyType());
          ps.setObject(5, x.partyId());
          ps.setString(6, x.partyName());
          ps.setString(7, x.crossRefNumber());
          ps.setObject(8, x.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create cross reference");
    return x;
  }

  public List<ItemCrossReference> listCrossReferences(
      UUID tenantId, UUID variantId, String partyType) {
    if (partyType != null) {
      return query(
          "SELECT id, tenant_id, variant_id, party_type, party_id, party_name,"
              + " cross_ref_number, created_at"
              + " FROM item_cross_references"
              + " WHERE tenant_id = ? AND variant_id = ? AND party_type = ?"
              + " ORDER BY created_at",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setString(3, partyType);
          },
          ProductRepository::mapCrossReference,
          "list cross references by type");
    }
    return query(
        "SELECT id, tenant_id, variant_id, party_type, party_id, party_name,"
            + " cross_ref_number, created_at"
            + " FROM item_cross_references"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapCrossReference,
        "list cross references");
  }

  public boolean deleteCrossReference(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM item_cross_references WHERE tenant_id = ? AND id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete cross reference");
    return found[0] != null;
  }

  private static ItemCrossReference mapCrossReference(ResultSet rs) throws SQLException {
    return new ItemCrossReference(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("party_type"),
        rs.getObject("party_id", UUID.class),
        rs.getString("party_name"),
        rs.getString("cross_ref_number"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Item Relationships (Gap #32) ────────────────────────────────────────

  public ItemRelationship createRelationship(ItemRelationship r) {
    exec(
        "INSERT INTO item_relationships"
            + " (id, tenant_id, variant_id, related_variant_id, relationship_type, created_at)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, r.id());
          ps.setObject(2, r.tenantId());
          ps.setObject(3, r.variantId());
          ps.setObject(4, r.relatedVariantId());
          ps.setString(5, r.relationshipType());
          ps.setObject(6, r.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create item relationship");
    return r;
  }

  public List<ItemRelationship> listRelationships(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, related_variant_id, relationship_type, created_at"
            + " FROM item_relationships WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapRelationship,
        "list item relationships");
  }

  public boolean deleteRelationship(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM item_relationships WHERE tenant_id = ? AND id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete item relationship");
    return found[0] != null;
  }

  private static ItemRelationship mapRelationship(ResultSet rs) throws SQLException {
    return new ItemRelationship(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("related_variant_id", UUID.class),
        rs.getString("relationship_type"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
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
            "SELECT id, tenant_id, name, description, attributes, status, created_at"
                + " FROM item_templates WHERE tenant_id=? AND id=?",
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
        "SELECT id, tenant_id, name, description, attributes, status, created_at"
            + " FROM item_templates WHERE tenant_id=? AND status='ACTIVE' ORDER BY name",
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

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  public CatalogGroup createCatalogGroup(UUID tenantId, String name, String description) {
    Instant now = Instant.now();
    var g =
        new CatalogGroup(
            UUID.randomUUID(), tenantId, name, description, CatalogGroup.ACTIVE, now, now);
    exec(
        "INSERT INTO catalog_groups"
            + " (id, tenant_id, name, description, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, g.id());
          ps.setObject(2, g.tenantId());
          ps.setString(3, g.name());
          ps.setString(4, g.description());
          ps.setString(5, g.status());
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create catalog group");
    return g;
  }

  public Optional<CatalogGroup> findCatalogGroup(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, description, status, created_at, updated_at"
                + " FROM catalog_groups WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapCatalogGroup,
            "find catalog group")
        .stream()
        .findFirst();
  }

  public List<CatalogGroup> listCatalogGroups(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, description, status, created_at, updated_at"
            + " FROM catalog_groups WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapCatalogGroup,
        "list catalog groups");
  }

  public CatalogGroup deactivateCatalogGroup(UUID tenantId, UUID id) {
    exec(
        "UPDATE catalog_groups SET status = 'INACTIVE', updated_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate catalog group");
    return findCatalogGroup(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATALOG_GROUP_NOT_FOUND", "Catalog group not found"));
  }

  public CatalogGroupElement createCatalogGroupElement(CatalogGroupElement e) {
    exec(
        "INSERT INTO catalog_group_elements"
            + " (id, tenant_id, group_id, element_name, data_type, required,"
            + " default_val, sort_order, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, e.id());
          ps.setObject(2, e.tenantId());
          ps.setObject(3, e.groupId());
          ps.setString(4, e.elementName());
          ps.setString(5, e.dataType());
          ps.setBoolean(6, e.required());
          ps.setString(7, e.defaultVal());
          ps.setInt(8, e.sortOrder());
          ps.setObject(9, e.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create catalog group element");
    return e;
  }

  public List<CatalogGroupElement> listCatalogGroupElements(UUID tenantId, UUID groupId) {
    return query(
        "SELECT id, tenant_id, group_id, element_name, data_type, required,"
            + " default_val, sort_order, created_at"
            + " FROM catalog_group_elements"
            + " WHERE tenant_id = ? AND group_id = ?"
            + " ORDER BY sort_order, element_name",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, groupId);
        },
        ProductRepository::mapCatalogGroupElement,
        "list catalog group elements");
  }

  public boolean deleteCatalogGroupElement(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM catalog_group_elements WHERE tenant_id = ? AND id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete catalog group element");
    return found[0] != null;
  }

  public VariantCatalogAssignment createCatalogAssignment(VariantCatalogAssignment a) {
    Instant now = Instant.now();
    exec(
        "INSERT INTO variant_catalog_assignments"
            + " (id, tenant_id, variant_id, group_id, element_vals, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?)",
        ps -> {
          ps.setObject(1, a.id());
          ps.setObject(2, a.tenantId());
          ps.setObject(3, a.variantId());
          ps.setObject(4, a.groupId());
          ps.setString(5, a.elementVals() != null ? a.elementVals() : "{}");
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create catalog assignment");
    return findCatalogAssignment(a.tenantId(), a.variantId())
        .orElseThrow(
            () ->
                ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment not found after insert"));
  }

  public Optional<VariantCatalogAssignment> findCatalogAssignment(UUID tenantId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, variant_id, group_id, element_vals, created_at, updated_at"
                + " FROM variant_catalog_assignments WHERE tenant_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            ProductRepository::mapCatalogAssignment,
            "find catalog assignment")
        .stream()
        .findFirst();
  }

  public VariantCatalogAssignment updateCatalogAssignment(
      UUID tenantId, UUID variantId, String elementVals) {
    exec(
        "UPDATE variant_catalog_assignments SET element_vals = ?::jsonb, updated_at = ?"
            + " WHERE tenant_id = ? AND variant_id = ?",
        ps -> {
          ps.setString(1, elementVals != null ? elementVals : "{}");
          ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, variantId);
        },
        "update catalog assignment");
    return findCatalogAssignment(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant"));
  }

  public boolean deleteCatalogAssignment(UUID tenantId, UUID variantId) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_catalog_assignments WHERE tenant_id = ? AND variant_id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete catalog assignment");
    return found[0] != null;
  }

  // ─────────────────────────────────────────────── container types (Gap #37)

  public ContainerType createContainerType(
      UUID tenantId,
      String code,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO container_types"
            + " (id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
            + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, code.toUpperCase(java.util.Locale.ROOT));
          ps.setString(4, name);
          ps.setString(5, description);
          ps.setBigDecimal(6, lengthMm);
          ps.setBigDecimal(7, widthMm);
          ps.setBigDecimal(8, heightMm);
          ps.setBigDecimal(9, maxWeightKg);
          ps.setBigDecimal(10, tareWeightKg);
          if (maxUnits != null) ps.setInt(11, maxUnits);
          else ps.setNull(11, java.sql.Types.INTEGER);
          ps.setString(12, ContainerType.ACTIVE);
          ps.setObject(13, now.atOffset(ZoneOffset.UTC));
          ps.setObject(14, now.atOffset(ZoneOffset.UTC));
        },
        "create container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public Optional<ContainerType> findContainerType(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
                + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at"
                + " FROM container_types WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapContainerType,
            "find container type")
        .stream()
        .findFirst();
  }

  public List<ContainerType> listContainerTypes(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
            + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at"
            + " FROM container_types WHERE tenant_id=? AND status='ACTIVE' ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapContainerType,
        "list container types");
  }

  public ContainerType updateContainerType(
      UUID tenantId,
      UUID id,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits) {
    Instant now = Instant.now();
    exec(
        "UPDATE container_types SET name=?,description=?,length_mm=?,width_mm=?,height_mm=?,"
            + "max_weight_kg=?,tare_weight_kg=?,max_units=?,updated_at=?"
            + " WHERE tenant_id=? AND id=? AND status='ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setBigDecimal(3, lengthMm);
          ps.setBigDecimal(4, widthMm);
          ps.setBigDecimal(5, heightMm);
          ps.setBigDecimal(6, maxWeightKg);
          ps.setBigDecimal(7, tareWeightKg);
          if (maxUnits != null) ps.setInt(8, maxUnits);
          else ps.setNull(8, java.sql.Types.INTEGER);
          ps.setObject(9, now.atOffset(ZoneOffset.UTC));
          ps.setObject(10, tenantId);
          ps.setObject(11, id);
        },
        "update container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public ContainerType deactivateContainerType(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE container_types SET status='INACTIVE', updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public VariantContainerLink createVariantContainerLink(
      UUID tenantId, UUID variantId, UUID containerTypeId, int qtyPerContainer, boolean isPrimary) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO variant_container_links"
            + " (id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, variantId);
          ps.setObject(4, containerTypeId);
          ps.setInt(5, qtyPerContainer);
          ps.setBoolean(6, isPrimary);
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create variant container link");
    return findVariantContainerLink(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_LINK_NOT_FOUND", "Container link not found"));
  }

  public Optional<VariantContainerLink> findVariantContainerLink(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at"
                + " FROM variant_container_links WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapVariantContainerLink,
            "find variant container link")
        .stream()
        .findFirst();
  }

  public List<VariantContainerLink> listVariantContainerLinks(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at"
            + " FROM variant_container_links WHERE tenant_id=? AND variant_id=? ORDER BY is_primary DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapVariantContainerLink,
        "list variant container links");
  }

  public boolean deleteVariantContainerLink(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_container_links WHERE tenant_id=? AND id=? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete variant container link");
    return found[0] != null;
  }

  private static ContainerType mapContainerType(ResultSet rs) throws SQLException {
    return new ContainerType(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getBigDecimal("length_mm"),
        rs.getBigDecimal("width_mm"),
        rs.getBigDecimal("height_mm"),
        rs.getBigDecimal("max_weight_kg"),
        rs.getBigDecimal("tare_weight_kg"),
        rs.getObject("max_units") != null ? rs.getInt("max_units") : null,
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static VariantContainerLink mapVariantContainerLink(ResultSet rs) throws SQLException {
    return new VariantContainerLink(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("container_type_id", UUID.class),
        rs.getInt("qty_per_container"),
        rs.getBoolean("is_primary"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ─────────────────────────────────────────── item attribute groups (Gap #36)

  public List<ItemAttributeGroup> listAttributeGroups() {
    return query(
        "SELECT group_code, name, description FROM item_attribute_groups ORDER BY group_code",
        ps -> {},
        ProductRepository::mapAttributeGroup,
        "list attribute groups");
  }

  public Optional<ItemAttributeGroup> findAttributeGroup(String groupCode) {
    return query(
            "SELECT group_code, name, description FROM item_attribute_groups WHERE group_code = ?",
            ps -> ps.setString(1, groupCode),
            ProductRepository::mapAttributeGroup,
            "find attribute group")
        .stream()
        .findFirst();
  }

  public List<ItemAttributeGroupField> listAttributeGroupFields(String groupCode) {
    return query(
        "SELECT group_code, field_code, label, data_type, required, sort_order"
            + " FROM item_attribute_group_fields WHERE group_code = ? ORDER BY sort_order",
        ps -> ps.setString(1, groupCode),
        ProductRepository::mapAttributeGroupField,
        "list attribute group fields");
  }

  public VariantAttributeGroupValues upsertVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode, String values) {
    Instant now = Instant.now();
    exec(
        "INSERT INTO variant_attribute_group_values"
            + " (id, tenant_id, variant_id, group_code, values, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?)"
            + " ON CONFLICT (tenant_id, variant_id, group_code)"
            + " DO UPDATE SET values = EXCLUDED.values, updated_at = EXCLUDED.updated_at",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, tenantId);
          ps.setObject(3, variantId);
          ps.setString(4, groupCode);
          ps.setString(5, values != null ? values : "{}");
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "upsert variant attribute group values");
    return findVariantAttributeGroupValues(tenantId, variantId, groupCode)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_VALUES_NOT_FOUND", "Attribute group values not found"));
  }

  public Optional<VariantAttributeGroupValues> findVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    return query(
            "SELECT id, tenant_id, variant_id, group_code, values::text, created_at, updated_at"
                + " FROM variant_attribute_group_values"
                + " WHERE tenant_id = ? AND variant_id = ? AND group_code = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setString(3, groupCode);
            },
            ProductRepository::mapVariantAttributeGroupValues,
            "find variant attribute group values")
        .stream()
        .findFirst();
  }

  public List<VariantAttributeGroupValues> listVariantAttributeGroupValues(
      UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, group_code, values::text, created_at, updated_at"
            + " FROM variant_attribute_group_values"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY group_code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapVariantAttributeGroupValues,
        "list variant attribute group values");
  }

  public boolean deleteVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_attribute_group_values"
            + " WHERE tenant_id = ? AND variant_id = ? AND group_code = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setString(3, groupCode);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete variant attribute group values");
    return found[0] != null;
  }

  private static ItemAttributeGroup mapAttributeGroup(ResultSet rs) throws SQLException {
    return new ItemAttributeGroup(
        rs.getString("group_code"), rs.getString("name"), rs.getString("description"));
  }

  private static ItemAttributeGroupField mapAttributeGroupField(ResultSet rs) throws SQLException {
    return new ItemAttributeGroupField(
        rs.getString("group_code"),
        rs.getString("field_code"),
        rs.getString("label"),
        rs.getString("data_type"),
        rs.getBoolean("required"),
        rs.getInt("sort_order"));
  }

  private static VariantAttributeGroupValues mapVariantAttributeGroupValues(ResultSet rs)
      throws SQLException {
    return new VariantAttributeGroupValues(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("group_code"),
        rs.getString("values"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CatalogGroup mapCatalogGroup(ResultSet rs) throws SQLException {
    return new CatalogGroup(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CatalogGroupElement mapCatalogGroupElement(ResultSet rs) throws SQLException {
    return new CatalogGroupElement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("element_name"),
        rs.getString("data_type"),
        rs.getBoolean("required"),
        rs.getString("default_val"),
        rs.getInt("sort_order"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static VariantCatalogAssignment mapCatalogAssignment(ResultSet rs) throws SQLException {
    return new VariantCatalogAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("element_vals"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ──────────────────────────────────────────── category sets (Gap #39) ──────

  public CategorySet createCategorySet(CategorySet s) {
    exec(
        "INSERT INTO category_sets"
            + " (id, tenant_id, name, description, purpose, default_cat_id, controlled, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setString(3, s.name());
          ps.setString(4, s.description());
          ps.setString(5, s.purpose());
          ps.setObject(6, s.defaultCatId());
          ps.setBoolean(7, s.controlled());
          ps.setString(8, s.status());
        },
        "create category set");
    return findCategorySet(s.tenantId(), s.id()).orElseThrow();
  }

  public Optional<CategorySet> findCategorySet(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, description, purpose, default_cat_id, controlled,"
                + " status, created_at, updated_at"
                + " FROM category_sets WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ProductRepository::mapCategorySet,
            "find category set")
        .stream()
        .findFirst();
  }

  public List<CategorySet> listCategorySets(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, description, purpose, default_cat_id, controlled,"
            + " status, created_at, updated_at"
            + " FROM category_sets WHERE tenant_id = ? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ProductRepository::mapCategorySet,
        "list category sets");
  }

  public CategorySet updateCategorySet(
      UUID tenantId,
      UUID id,
      String name,
      String description,
      String purpose,
      UUID defaultCatId,
      boolean controlled,
      String status) {
    exec(
        "UPDATE category_sets"
            + " SET name = COALESCE(?, name), description = COALESCE(?, description),"
            + " purpose = COALESCE(?, purpose), default_cat_id = ?, controlled = ?,"
            + " status = COALESCE(?, status), updated_at = now()"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setString(3, purpose);
          ps.setObject(4, defaultCatId);
          ps.setBoolean(5, controlled);
          ps.setString(6, status);
          ps.setObject(7, tenantId);
          ps.setObject(8, id);
        },
        "update category set");
    return findCategorySet(tenantId, id).orElseThrow();
  }

  public boolean deleteCategorySet(UUID tenantId, UUID id) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM category_sets WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            return ps.executeUpdate() > 0;
          }
        },
        "delete category set");
  }

  public CategorySetMember addCategorySetMember(CategorySetMember m) {
    exec(
        "INSERT INTO category_set_members (id, tenant_id, set_id, category_id)"
            + " VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, set_id, category_id) DO NOTHING",
        ps -> {
          ps.setObject(1, m.id());
          ps.setObject(2, m.tenantId());
          ps.setObject(3, m.setId());
          ps.setObject(4, m.categoryId());
        },
        "add category set member");
    return query(
            "SELECT id, tenant_id, set_id, category_id, created_at"
                + " FROM category_set_members"
                + " WHERE tenant_id = ? AND set_id = ? AND category_id = ?",
            ps -> {
              ps.setObject(1, m.tenantId());
              ps.setObject(2, m.setId());
              ps.setObject(3, m.categoryId());
            },
            ProductRepository::mapCategorySetMember,
            "find category set member")
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public List<CategorySetMember> listCategorySetMembers(UUID tenantId, UUID setId) {
    return query(
        "SELECT id, tenant_id, set_id, category_id, created_at"
            + " FROM category_set_members WHERE tenant_id = ? AND set_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, setId);
        },
        ProductRepository::mapCategorySetMember,
        "list category set members");
  }

  public boolean deleteCategorySetMember(UUID tenantId, UUID setId, UUID categoryId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM category_set_members"
                      + " WHERE tenant_id = ? AND set_id = ? AND category_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, setId);
            ps.setObject(3, categoryId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete category set member");
  }

  public VariantCategorySetAssignment upsertVariantCategorySetAssignment(
      VariantCategorySetAssignment a) {
    exec(
        "INSERT INTO variant_category_set_assignments"
            + " (id, tenant_id, variant_id, set_id, category_id) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, variant_id, set_id)"
            + " DO UPDATE SET category_id = EXCLUDED.category_id, updated_at = now()",
        ps -> {
          ps.setObject(1, a.id());
          ps.setObject(2, a.tenantId());
          ps.setObject(3, a.variantId());
          ps.setObject(4, a.setId());
          ps.setObject(5, a.categoryId());
        },
        "upsert variant category set assignment");
    return query(
            "SELECT id, tenant_id, variant_id, set_id, category_id, created_at, updated_at"
                + " FROM variant_category_set_assignments"
                + " WHERE tenant_id = ? AND variant_id = ? AND set_id = ?",
            ps -> {
              ps.setObject(1, a.tenantId());
              ps.setObject(2, a.variantId());
              ps.setObject(3, a.setId());
            },
            ProductRepository::mapVariantCategorySetAssignment,
            "find variant category set assignment")
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public List<VariantCategorySetAssignment> listVariantCategorySetAssignments(
      UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, set_id, category_id, created_at, updated_at"
            + " FROM variant_category_set_assignments"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY set_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ProductRepository::mapVariantCategorySetAssignment,
        "list variant category set assignments");
  }

  public boolean deleteVariantCategorySetAssignment(UUID tenantId, UUID variantId, UUID setId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM variant_category_set_assignments"
                      + " WHERE tenant_id = ? AND variant_id = ? AND set_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setObject(3, setId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete variant category set assignment");
  }

  private static CategorySet mapCategorySet(ResultSet rs) throws SQLException {
    UUID defCat = (UUID) rs.getObject("default_cat_id");
    return new CategorySet(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("purpose"),
        defCat,
        rs.getBoolean("controlled"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CategorySetMember mapCategorySetMember(ResultSet rs) throws SQLException {
    return new CategorySetMember(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("set_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static VariantCategorySetAssignment mapVariantCategorySetAssignment(ResultSet rs)
      throws SQLException {
    return new VariantCategorySetAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("set_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
