package com.shelfj.product.repo;

import com.shelfj.ids.Ids;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.domain.Domain.VariantWithProduct;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.service.RedisCache;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
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

  @Inject RedisCache cache;

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
    cache.evict(productKey(p.tenantId(), p.id()));
    return updated;
  }

  public Optional<Product> findProduct(UUID tenantId, UUID id) {
    String cacheKey = productKey(tenantId, id);
    String cached = cache.get(cacheKey);
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
    fresh.ifPresent(p -> cache.put(cacheKey, encodeProduct(p), PRODUCT_TTL_SECONDS));
    return fresh;
  }

  // ── product image (one primary image per product, BYTEA) ──────────────────

  public void upsertProductImage(UUID tenantId, UUID productId, String contentType, byte[] bytes) {
    exec(
        "INSERT INTO product_images (product_id, tenant_id, content_type, bytes, updated_at)"
            + " VALUES (?,?,?,?,now())"
            + " ON CONFLICT (product_id)"
            + " DO UPDATE SET content_type = EXCLUDED.content_type, bytes = EXCLUDED.bytes,"
            + " updated_at = now()"
            + " WHERE product_images.tenant_id = EXCLUDED.tenant_id",
        ps -> {
          ps.setObject(1, productId);
          ps.setObject(2, tenantId);
          ps.setString(3, contentType);
          ps.setBytes(4, bytes);
        },
        "upsert product image");
  }

  public Optional<com.shelfj.product.domain.Domain.ProductImage> findProductImage(
      UUID tenantId, UUID productId) {
    return query(
            "SELECT product_id, content_type, bytes FROM product_images"
                + " WHERE tenant_id = ? AND product_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, productId);
            },
            rs ->
                new com.shelfj.product.domain.Domain.ProductImage(
                    rs.getObject("product_id", UUID.class),
                    rs.getString("content_type"),
                    rs.getBytes("bytes")),
            "find product image")
        .stream()
        .findFirst();
  }

  public void deleteProductImage(UUID tenantId, UUID productId) {
    exec(
        "DELETE FROM product_images WHERE tenant_id = ? AND product_id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        "delete product image");
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
                    "INSERT INTO product_stores (id, tenant_id, product_id, store_id)"
                        + " VALUES (?, ?, ?, ?)")) {
              for (UUID sid : storeIds) {
                ins.setObject(1, Ids.newId());
                ins.setObject(2, tenantId);
                ins.setObject(3, productId);
                ins.setObject(4, sid);
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
                  "INSERT INTO product_stores (id, tenant_id, product_id, store_id)"
                      + " VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, product_id, store_id) DO NOTHING")) {
            for (UUID sid : storeIds) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, productId);
              ps.setObject(4, sid);
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
    // RETURNING, not a second SELECT: a re-read could hand this caller a concurrent writer's row,
    // and could not tell "updated" from "matched nothing because the variant is delisted".
    return query(
            "UPDATE product_variants SET sku=?, barcode=?, manufacturer_pn=?, attributes=?, unit=?,"
                + " updated_at=? WHERE tenant_id=? AND id=? AND status='ACTIVE'"
                + " RETURNING id, tenant_id, product_id, sku, barcode, manufacturer_pn, attributes,"
                + " unit, status, created_at, updated_at",
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
            ProductRepository::mapVariant,
            "update variant")
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                ApiException.conflict(
                    "VARIANT_NOT_ACTIVE", "A delisted variant cannot be edited; relist it first"));
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
}
