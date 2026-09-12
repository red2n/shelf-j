package com.shelfj.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import com.shelfj.test.RedisSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the product catalog against real Postgres (Testcontainers): create brand →
 * category → product → variant, public browse, tenant isolation, duplicate SKU 409, delist removes
 * from public list.
 */
@HelidonTest
class CatalogIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "product");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");

    REDIS = RedisSupport.start();
    System.setProperty("shelfj.redis.host", REDIS.host());
    System.setProperty("shelfj.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("shelfj.redis.password", "");
  }

  private static final String TENANT_A = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String TENANT_B = "01a090ae-611e-700f-b645-a14095230b77";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
    REDIS.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get(String.class);
  }

  /** Like {@link #get} but for /admin/... paths, which require a staff role. */
  private String getAdmin(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private Response listProductsAdmin(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/products").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Bypasses the app entirely — proves a read came from cache rather than the DB. */
  private static void rawUpdateProductName(String productId, String name) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement("UPDATE product.products SET name = ? WHERE id = ?::uuid")) {
      ps.setString(1, name);
      ps.setString(2, productId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void catalogFlowAndIsolation() {
    // product
    Response p = post("/admin/products", "{\"name\":\"Rice 5kg\"}", TENANT_A);
    assertThat(p.getStatus(), is(201));
    String productId = field(p.readEntity(String.class), "id");

    // variant
    Response v =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RICE-5KG\"}", TENANT_A);
    assertThat(v.getStatus(), is(201));

    // duplicate sku → 409
    Response dup =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RICE-5KG\"}", TENANT_A);
    assertThat(dup.getStatus(), is(409));

    // public list shows it (tenant A)
    assertThat(get("/catalog/products", TENANT_A), containsString("Rice 5kg"));

    // tenant B sees nothing (isolation)
    assertThat(get("/catalog/products", TENANT_B), not(containsString("Rice 5kg")));

    // delist removes from public list
    target
        .path("/admin/products/" + productId)
        .request()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-Roles", "OWNER")
        .delete();
    assertThat(get("/catalog/products", TENANT_A), not(containsString("Rice 5kg")));
  }

  /**
   * The update used to run its UPDATE and then re-read the row. Against a delisted variant the
   * UPDATE matched nothing, and the re-read still answered 200 with the old values, as if the edit
   * had been saved.
   */
  @Test
  void aVariantEditIsSavedOrRefusedNeverSilentlyDropped() {
    String productId =
        field(
            post("/admin/products", "{\"name\":\"Kettle\"}", TENANT_A).readEntity(String.class),
            "id");
    String variantId =
        field(
            post("/admin/products/" + productId + "/variants", "{\"sku\":\"KETTLE-1\"}", TENANT_A)
                .readEntity(String.class),
            "id");
    String path = "/admin/products/" + productId + "/variants/" + variantId;

    Response edited = put(path, "{\"sku\":\"KETTLE-1\",\"manufacturerPn\":\"MFR-A\"}", TENANT_A);
    assertThat(edited.getStatus(), is(200));
    assertThat(edited.readEntity(String.class), containsString("\"manufacturerPn\":\"MFR-A\""));

    target.path(path).request().header("X-Tenant-Id", TENANT_A).header("X-Roles", "OWNER").delete();
    Response afterDelist =
        put(path, "{\"sku\":\"KETTLE-1\",\"manufacturerPn\":\"MFR-B\"}", TENANT_A);
    assertThat(afterDelist.getStatus(), is(409));
    assertThat(afterDelist.readEntity(String.class), containsString("VARIANT_NOT_ACTIVE"));
    assertThat(getAdmin(path, TENANT_A), containsString("\"manufacturerPn\":\"MFR-A\""));
  }

  @Test
  void resolveVariantsReturnsNameAndSkuAndIsolatesTenants() {
    Response p = post("/admin/products", "{\"name\":\"Resolve Me\"}", TENANT_A);
    String productId = field(p.readEntity(String.class), "id");
    Response v =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RESOLVE-1\"}", TENANT_A);
    String variantId = field(v.readEntity(String.class), "id");

    // Resolve maps the variant UUID to its product name + SKU.
    String resolved = resolve(variantId, TENANT_A);
    assertThat(resolved, containsString("Resolve Me"));
    assertThat(resolved, containsString("RESOLVE-1"));
    assertThat(resolved, containsString(variantId));

    // Another tenant cannot resolve tenant A's variant (isolation).
    assertThat(resolve(variantId, TENANT_B), not(containsString("Resolve Me")));

    // A malformed id is a 400, not a 500.
    Response bad =
        target
            .path("/admin/products/variants/resolve")
            .queryParam("ids", "not-a-uuid")
            .request()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(bad.getStatus(), is(400));
  }

  private String resolve(String variantId, String tenant) {
    return target
        .path("/admin/products/variants/resolve")
        .queryParam("ids", variantId)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  @Test
  void getProductIsCachedAndInvalidatedOnUpdate() {
    Response p = post("/admin/products", "{\"name\":\"Cached Widget\"}", TENANT_A);
    assertThat(p.getStatus(), is(201));
    String productId = field(p.readEntity(String.class), "id");

    // first read — populates the cache
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Cached Widget"));

    // mutate the row directly in Postgres, bypassing the app and its cache eviction
    rawUpdateProductName(productId, "Mutated Behind Cache");

    // still served from cache — proves the read isn't hitting Postgres every time
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Cached Widget"));

    // a real update goes through the app, which evicts the cache key
    Response updated =
        put(
            "/admin/products/" + productId,
            "{\"name\":\"Updated Widget\",\"sellableOnline\":true,\"sellablePos\":true}",
            TENANT_A);
    assertThat(updated.getStatus(), is(200));

    // next read reflects the update, not the raw mutation — cache was invalidated, not just expired
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Updated Widget"));
    assertThat(
        get("/catalog/products/" + productId, TENANT_A),
        not(containsString("Mutated Behind Cache")));
  }

  @Test
  void bulkImportSharedCategoryResolvesToOneRowAcrossProducts() {
    // Two products in the same import sharing a category and brand — regression guard for the
    // category/brand name-to-id caching in ProductService.bulkImport: both should resolve to the
    // same category/brand row, not each trigger their own independent lookup gone wrong.
    Response r =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\"Grocery\"}],"
                + "\"products\":["
                + "{\"name\":\"Rice\",\"categoryName\":\"Grocery\",\"brandName\":\"Acme\","
                + "\"variants\":[{\"sku\":\"BULK-RICE\"}]},"
                + "{\"name\":\"Pasta\",\"categoryName\":\"Grocery\",\"brandName\":\"Acme\","
                + "\"variants\":[{\"sku\":\"BULK-PASTA\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"categoriesCreated\":1"));
    assertThat(body, containsString("\"productsCreated\":2"));
    assertThat(body, containsString("\"variantsCreated\":2"));
    assertThat(body, containsString("\"errors\":[]"));

    String riceProductId = fieldNear(body, "\"sku\":\"BULK-RICE\"", "productId");
    String pastaProductId = fieldNear(body, "\"sku\":\"BULK-PASTA\"", "productId");

    String riceCategoryId =
        field(getAdmin("/admin/products/" + riceProductId, TENANT_A), "categoryId");
    String pastaCategoryId =
        field(getAdmin("/admin/products/" + pastaProductId, TENANT_A), "categoryId");
    assertThat(riceCategoryId, is(pastaCategoryId));
  }

  /**
   * F12 follow-up (AUDIT.md): {@code ProductService.bulkImport} never called {@code
   * Validations.validate()} per-item, so per-item constraints (e.g. {@code
   * ImportCategoryRequest.name @NotBlank}) were dead code — a blank category name would silently
   * create a blank-named category instead of failing. Proves the fix without breaking the
   * documented partial-success contract: the bad category lands in {@code errors}, but the
   * well-formed product in the same request still imports.
   */
  @Test
  void bulkImportRejectsAnInvalidItemButStillImportsTheRest() {
    Response r =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\"\"}],"
                + "\"products\":["
                + "{\"name\":\"Good Product\",\"variants\":[{\"sku\":\"BULK-GOOD-SKU\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"categoriesCreated\":0"));
    assertThat(body, containsString("\"productsCreated\":1"));
    assertThat(body, containsString("\"variantsCreated\":1"));
    assertThat(body, not(containsString("\"errors\":[]")));
    assertThat(body, containsString("\"item\":\"category:\""));
    assertThat(body, containsString("Request validation failed"));

    // The blank-named category was never created — not silently persisted as "".
    assertThat(getAdmin("/admin/categories", TENANT_A), not(containsString("\"name\":\"\"")));
  }

  /**
   * Same F12 follow-up, at the nested variant level: {@code ImportVariantRequest.sku @NotBlank} was
   * equally dead code inside the products loop's inner variant loop. {@code
   * ImportProductRequest.variants} already carries {@code @Valid} (prior F12 fix), so validating
   * the whole product cascades into its variants — a product with a blank-SKU variant fails as one
   * whole item (neither the product nor any of its variants are created), but a separate,
   * well-formed product in the same batch still imports. That is the granularity the
   * partial-success contract actually promises at this layer: whole-item isolation, not
   * per-sibling-variant isolation within a single bad product.
   */
  @Test
  void bulkImportRejectsAnInvalidVariantAndStillImportsOtherProducts() {
    Response r =
        post(
            "/admin/import",
            "{\"products\":["
                + "{\"name\":\"Bad Product\",\"variants\":[{\"sku\":\"\"}]},"
                + "{\"name\":\"Good Product\",\"variants\":[{\"sku\":\"BULK-SIBLING-SKU\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"productsCreated\":1"));
    assertThat(body, containsString("\"variantsCreated\":1"));
    assertThat(body, containsString("BULK-SIBLING-SKU"));
    assertThat(body, not(containsString("\"errors\":[]")));
    assertThat(body, containsString("\"item\":\"product:Bad Product\""));
    assertThat(body, containsString("Request validation failed"));
  }

  @Test
  void blankNameIs400() {
    Response bad = post("/admin/products", "{\"name\":\"\"}", TENANT_A);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  /**
   * Characterization coverage for {@code BrandRepository} (extracted from {@code ProductRepository}
   * — F2, AUDIT.md) — pins down current CRUD + tenant-isolation behavior since these endpoints
   * previously had none beyond the incidental exercise inside bulk-import.
   */
  @Test
  void brandsCrudAndTenantIsolation() {
    Response created = post("/admin/brands", "{\"name\":\"Acme\"}", TENANT_A);
    assertThat(created.getStatus(), is(201));
    String brandId = field(created.readEntity(String.class), "id");

    assertThat(getAdmin("/admin/brands", TENANT_A), containsString("Acme"));
    assertThat(getAdmin("/admin/brands", TENANT_B), not(containsString("Acme")));

    Response renamed = put("/admin/brands/" + brandId, "{\"name\":\"Acme Renamed\"}", TENANT_A);
    assertThat(renamed.getStatus(), is(200));
    assertThat(getAdmin("/admin/brands/" + brandId, TENANT_A), containsString("Acme Renamed"));

    Response deactivated = delete("/admin/brands/" + brandId, TENANT_A);
    assertThat(deactivated.getStatus(), is(200));
    assertThat(getAdmin("/admin/brands", TENANT_A), not(containsString("Acme Renamed")));
  }

  /**
   * Characterization coverage for {@code CategoryRepository} (extracted from {@code
   * ProductRepository} — F2, AUDIT.md): parent/child linkage, tenant isolation, bad-parent 400, and
   * rename/deactivate — previously uncovered beyond bulk-import's incidental exercise.
   */
  @Test
  void categoriesCrudWithParentAndTenantIsolation() {
    Response parent = post("/admin/categories", "{\"name\":\"Beverages\"}", TENANT_A);
    assertThat(parent.getStatus(), is(201));
    String parentId = field(parent.readEntity(String.class), "id");

    Response child =
        post(
            "/admin/categories",
            "{\"name\":\"Soft Drinks\",\"parentId\":\"" + parentId + "\"}",
            TENANT_A);
    assertThat(child.getStatus(), is(201));
    String childBody = child.readEntity(String.class);
    String childId = field(childBody, "id");
    assertThat(field(childBody, "parentId"), is(parentId));

    assertThat(getAdmin("/admin/categories", TENANT_A), containsString("Soft Drinks"));
    assertThat(getAdmin("/admin/categories", TENANT_B), not(containsString("Soft Drinks")));

    // Unknown parentId is a 400 (PARENT_NOT_FOUND), not a 500.
    Response badParent =
        post(
            "/admin/categories",
            "{\"name\":\"Orphan\",\"parentId\":\"01a090ae-611e-701d-9d60-a9d7516ed03b\"}",
            TENANT_A);
    assertThat(badParent.getStatus(), is(400));

    Response renamed =
        put(
            "/admin/categories/" + childId,
            "{\"name\":\"Fizzy Drinks\",\"parentId\":\"" + parentId + "\"}",
            TENANT_A);
    assertThat(renamed.getStatus(), is(200));
    assertThat(getAdmin("/admin/categories/" + childId, TENANT_A), containsString("Fizzy Drinks"));

    Response deactivated = delete("/admin/categories/" + childId, TENANT_A);
    assertThat(deactivated.getStatus(), is(200));
    assertThat(getAdmin("/admin/categories", TENANT_A), not(containsString("Fizzy Drinks")));
  }

  private Response delete(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .delete();
  }

  @Test
  void listProductsAdminPaginatesWithCursor() {
    // Dedicated tenant so products created by other tests never leak into these pages.
    String tenant = "01a090ae-611e-7011-ae7d-1bd68c966ff6";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response r = post("/admin/products", "{\"name\":\"Paginate " + i + "\"}", tenant);
      assertThat(r.getStatus(), is(201));
      allIds.add(field(r.readEntity(String.class), "id"));
    }

    Response p1 = listProductsAdmin(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listProductsAdmin(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allIds));
  }

  @Test
  void categorySetMemberAndAssignmentEndpointsRejectBlankIds() {
    Response csR =
        post(
            "/admin/category-sets",
            "{\"name\":\"Seasonal\",\"purpose\":\"MERCHANDISING\",\"controlled\":false}",
            TENANT_A);
    assertThat(csR.getStatus(), is(201));
    String setId = field(csR.readEntity(String.class), "id");

    // AddCategorySetMemberRequest.categoryId is @NotBlank — an empty string must be rejected.
    Response memberR =
        post("/admin/category-sets/" + setId + "/members", "{\"categoryId\":\"\"}", TENANT_A);
    assertThat(memberR.getStatus(), is(400));

    // AssignVariantCategorySetRequest.setId/categoryId are @NotBlank.
    String variantId = "01a090ae-611e-701c-979a-c9c9a3b8be89";
    Response assignR =
        post(
            "/admin/products/variants/" + variantId + "/category-set-assignments",
            "{\"setId\":\"\",\"categoryId\":\"\"}",
            TENANT_A);
    assertThat(assignR.getStatus(), is(400));
  }

  private static java.util.Set<String> extractAllIds(String json) {
    var ids = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"id\":\"", from);
      if (start < 0) break;
      start += 6;
      int end = json.indexOf('"', start);
      ids.add(json.substring(start, end));
      from = end;
    }
    return ids;
  }

  /** Returns meta.nextCursor, or null when the field is absent/null (no further page). */
  private static String extractNextCursor(String json) {
    int key = json.indexOf("\"nextCursor\":");
    if (key < 0) return null;
    int valueStart = key + "\"nextCursor\":".length();
    if (json.startsWith("null", valueStart)) return null;
    int start = json.indexOf('"', valueStart) + 1;
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  /**
   * Find {@code name} in the JSON object that contains {@code marker}. JSON-B serialises record
   * components alphabetically, so a field can appear before or after the marker within the same
   * object — this extracts the enclosing object first rather than assuming a scan direction.
   */
  private static String fieldNear(String json, String marker, String name) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    int objStart = json.lastIndexOf('{', m);
    int objEnd = json.indexOf('}', m);
    String obj = json.substring(objStart, objEnd + 1);
    return field(obj, name);
  }
}
