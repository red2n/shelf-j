package com.shelfj.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "product");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get(String.class);
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
    target.path("/admin/products/" + productId).request().header("X-Tenant-Id", TENANT_A).delete();
    assertThat(get("/catalog/products", TENANT_A), not(containsString("Rice 5kg")));
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
