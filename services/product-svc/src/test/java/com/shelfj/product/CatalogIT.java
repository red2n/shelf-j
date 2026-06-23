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

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

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
  void blankNameIs400() {
    Response bad = post("/admin/products", "{\"name\":\"\"}", TENANT_A);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
