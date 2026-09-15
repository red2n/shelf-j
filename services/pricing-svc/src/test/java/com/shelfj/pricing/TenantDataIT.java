package com.shelfj.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.shelfj.ids.Ids;
import com.shelfj.service.TenantDataErasureHandler;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A business's tenant data leaving and arriving (21.14, EU Data Act ch.VI), on pricing-svc: the
 * owner's manifest and pages, an export erased and imported into a fresh business that reads back
 * the same rows, the append-only price history letting only that erasure through, and every way an
 * import or a read is refused or abused.
 */
@HelidonTest
class TenantDataIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub STUB;

  static {
    PG = PostgresSupport.start().wire("pricing");
    STUB = TenantSvcStub.start();
  }

  @Inject WebTarget target;
  @Inject TenantDataErasureHandler erasure;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── the business leaving ────────────────────────────────────────────────────

  @Test
  @DisplayName("Exported, erased and imported into a fresh business, the rows read back the same")
  void roundTrip() throws Exception {
    String shop = shop();
    priced(shop, 5);
    Map<String, JsonObject> before = stableManifest(shop);
    assertThat(before.get("price_lists").getInt("rows"), is(1));
    assertThat(before.get("price_list_items").getInt("rows"), is(5));
    assertThat(before.get("price_list_item_prices").getInt("rows"), is(5));

    // The export, table by table in the manifest's order, page by page.
    Map<String, List<JsonArray>> export = new LinkedHashMap<>();
    for (String table : before.keySet()) export.put(table, pages(shop, table, 2));
    assertThat(export.get("price_list_items").size(), is(3));
    JsonObject row = export.get("price_list_items").get(0).getJsonObject(0);
    assertThat(row, hasKey("variant_id"));
    assertThat(row.getString("tenant_id"), is(shop));

    // The retrieval period ends: erased everywhere here, through the append-only history.
    String eventId = Ids.newId().toString();
    erasure.handle(erasureDue(eventId, shop));
    for (JsonObject t : manifest(shop).values()) {
      assertThat(t.getString("name"), t.getInt("rows"), is(0));
    }
    List<String> evidence = outbox("TenantDataErased");
    assertThat(evidence.size(), is(1));
    assertThat(evidence.get(0), containsString("\"erasureEventId\":\"" + eventId + "\""));
    assertThat(evidence.get(0), containsString("\"price_list_item_prices\":5"));
    // Again, as a redelivery would: nothing more to erase, and the same evidence id.
    erasure.handle(erasureDue(eventId, shop));
    List<String> twice = outbox("TenantDataErased");
    assertThat(twice.size(), is(2));
    assertThat(twice.get(1), containsString("\"rows\":0"));
    assertThat(eventIdOf(twice.get(1)), is(eventIdOf(twice.get(0))));

    // A fresh business takes the export, and reads back every table as it was.
    String fresh = shop();
    for (var e : export.entrySet()) {
      for (JsonArray page : e.getValue()) {
        Response r = importPage(fresh, e.getKey(), page, "OWNER");
        String body = r.readEntity(String.class);
        assertThat(e.getKey() + ": " + body, r.getStatus(), is(200));
      }
    }
    Map<String, JsonObject> after = manifest(fresh);
    for (var e : before.entrySet()) {
      JsonObject was = e.getValue();
      JsonObject now = after.get(e.getKey());
      assertThat(e.getKey(), now.getInt("rows"), is(was.getInt("rows")));
      assertThat(e.getKey(), now.getString("checksum"), is(was.getString("checksum")));
    }
    assertThat(
        pages(fresh, "price_list_items", 100).get(0).getJsonObject(0).getString("tenant_id"),
        is(fresh));
  }

  @Test
  @DisplayName("The price history still refuses every other delete, and an erasure of someone else")
  void appendOnlyStillHolds() throws Exception {
    String shop = shop();
    priced(shop, 1);
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      c.setAutoCommit(false);
      try (var st = c.createStatement()) {
        SQLException plain =
            org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class,
                () ->
                    st.executeUpdate(
                        "DELETE FROM pricing.price_list_item_prices WHERE tenant_id = '"
                            + shop
                            + "'"));
        assertThat(plain.getMessage(), containsString("append-only"));
      }
      c.rollback();
      try (var st = c.createStatement()) {
        st.execute("SELECT set_config('shelfj.erasing_tenant', '" + Ids.newId() + "', true)");
        SQLException other =
            org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class,
                () ->
                    st.executeUpdate(
                        "DELETE FROM pricing.price_list_item_prices WHERE tenant_id = '"
                            + shop
                            + "'"));
        assertThat(other.getMessage(), containsString("append-only"));
      }
      c.rollback();
      try (var st = c.createStatement()) {
        st.execute("SELECT set_config('shelfj.erasing_tenant', '" + shop + "', true)");
        SQLException update =
            org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class,
                () ->
                    st.executeUpdate(
                        "UPDATE pricing.price_list_item_prices SET price = 0 WHERE tenant_id = '"
                            + shop
                            + "'"));
        assertThat(update.getMessage(), containsString("append-only"));
      }
      c.rollback();
    }
    assertThat(manifest(shop).get("price_list_item_prices").getInt("rows"), is(1));
  }

  // ── what is refused ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("Only the owner reads it; a rival sees none of it; what is left out is named")
  void readsAreTheOwners() {
    String shop = shop();
    priced(shop, 2);
    for (String role : List.of("MANAGER", "CASHIER", "STOREKEEPER", "CUSTOMER")) {
      assertThat(role, get("/admin/tenant-data", shop, role).getStatus(), is(403));
      assertThat(
          role, get("/admin/tenant-data/tables/price_lists", shop, role).getStatus(), is(403));
    }
    String rival = shop();
    assertThat(manifest(rival).get("price_list_items").getInt("rows"), is(0));
    assertThat(pages(rival, "price_list_items", 10).get(0).size(), is(0));

    JsonObject manifest = data(get("/admin/tenant-data", shop, "OWNER"));
    assertThat(manifest.getString("format"), startsWith("shelfj-tenant-data/1"));
    JsonObject columns = manifest.getJsonObject("excludedColumns");
    assertThat(
        columns.getString("vat_registrations.hmrc_access_token"), containsString("credential"));
    assertThat(manifest.getJsonObject("excludedTables"), hasKey("outbox"));
    JsonObject registrations = manifest(shop).get("vat_registrations");
    assertThat(registrations.toString(), not(containsString("hmrc_access_token")));
    assertThat(manifest(shop).get("catalogue_products").getBoolean("derived"), is(true));
  }

  @Test
  @DisplayName("Tables and cursors are looked up, never trusted")
  void tablesAndCursors() {
    String shop = shop();
    for (String table :
        List.of(
            "outbox",
            "processed_events",
            "no_such_table",
            "price_lists' OR '1'='1",
            "price_lists\" --",
            "Price_Lists")) {
      Response r = get("/admin/tenant-data/tables/" + table, shop, "OWNER");
      assertThat(table, r.getStatus(), is(404));
    }
    for (String cursor :
        List.of(
            "not-base64!",
            b64("{\"id\":"),
            b64("{\"variant_id\":\"x\"}"),
            b64("[1,2]"),
            b64("{\"id\":\"x\",\"extra\":1}"))) {
      Response r =
          target
              .path("/admin/tenant-data/tables/price_lists")
              .queryParam("after", cursor)
              .request()
              .header("X-Tenant-Id", shop)
              .header("X-Roles", "OWNER")
              .get();
      String body = r.readEntity(String.class);
      assertThat(cursor + " " + body, r.getStatus(), is(400));
      assertThat(body, containsString("TENANT_DATA_CURSOR_INVALID"));
    }
  }

  @Test
  @DisplayName(
      "An import is refused twice, before its parents, malformed, too large or by a manager")
  void importsRefused() throws Exception {
    String source = shop();
    priced(source, 3);
    JsonArray lists = pages(source, "price_lists", 100).get(0);
    JsonArray items = pages(source, "price_list_items", 100).get(0);
    String eventId = Ids.newId().toString();
    erasure.handle(erasureDue(eventId, source));

    String fresh = shop();
    assertCode(
        importPage(fresh, "price_list_items", items, "OWNER"),
        409,
        "TENANT_DATA_IMPORT_MISSING_PARENT");
    assertCode(importPage(fresh, "price_lists", lists, "MANAGER"), 403, "");
    assertCode(importPage(fresh, "outbox", lists, "OWNER"), 404, "TENANT_DATA_TABLE_UNKNOWN");
    assertCode(
        post("/admin/tenant-data/tables/price_lists", "{}", fresh, "OWNER"),
        400,
        "TENANT_DATA_IMPORT_INVALID");
    assertCode(
        post("/admin/tenant-data/tables/price_lists", "{\"rows\":[1,2]}", fresh, "OWNER"),
        400,
        "TENANT_DATA_IMPORT_INVALID");
    JsonArrayBuilder many = Json.createArrayBuilder();
    for (int i = 0; i < 1001; i++) many.add(JsonValue.EMPTY_JSON_OBJECT);
    assertCode(
        importPage(fresh, "price_lists", many.build(), "OWNER"), 400, "TENANT_DATA_PAGE_TOO_LARGE");
    JsonObject broken =
        Json.createObjectBuilder(lists.getJsonObject(0))
            .add("effective_from", "not a time")
            .build();
    assertCode(
        importPage(fresh, "price_lists", Json.createArrayBuilder().add(broken).build(), "OWNER"),
        400,
        "TENANT_DATA_IMPORT_INVALID");

    // Ten imports of the same page at once load it once.
    List<Integer> statuses =
        com.shelfj.test.Concurrency.inParallel(
            10,
            () -> {
              Response r = importPage(fresh, "price_lists", lists, "OWNER");
              r.readEntity(String.class);
              return r.getStatus();
            });
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 200).count(), is(1L));
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 409).count(), is(9L));
    assertCode(
        importPage(fresh, "price_lists", lists, "OWNER"), 409, "TENANT_DATA_IMPORT_CONFLICT");
    assertThat(importPage(fresh, "price_list_items", items, "OWNER").getStatus(), is(200));
    assertThat(manifest(fresh).get("price_list_items").getInt("rows"), is(3));
    // The rows took the importing business as theirs: the source has none of them back.
    assertThat(manifest(source).get("price_list_items").getInt("rows"), is(0));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static String shop() {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");
    return tenant;
  }

  /** A price list for the shop with {@code n} variants on it. */
  private void priced(String shop, int n) {
    post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            shop,
            "OWNER")
        .close();
    Response list =
        post(
            "/admin/price-lists",
            "{\"name\":\"Export "
                + Ids.newId()
                + "\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            shop,
            "OWNER");
    String body = list.readEntity(String.class);
    assertThat(body, list.getStatus(), is(201));
    String listId = parse(body).getJsonObject("data").getString("id");
    for (int i = 0; i < n; i++) {
      String variant = Ids.newId().toString();
      post(
              "/product-vat-categories",
              "{\"variantId\":\"" + variant + "\",\"vatCode\":\"T1\"}",
              shop,
              "OWNER")
          .close();
      Response item =
          post(
              "/admin/price-lists/" + listId + "/items",
              "{\"variantId\":\"" + variant + "\",\"price\":" + (i + 1) + ".50,\"minQty\":1}",
              shop,
              "OWNER");
      String itemBody = item.readEntity(String.class);
      assertThat(itemBody, item.getStatus() < 300, is(true));
    }
  }

  private Map<String, JsonObject> manifest(String shop) {
    Response r = get("/admin/tenant-data", shop, "OWNER");
    JsonObject data = data(r);
    Map<String, JsonObject> tables = new LinkedHashMap<>();
    for (JsonValue v : data.getJsonArray("tables")) {
      tables.put(v.asJsonObject().getString("name"), v.asJsonObject());
    }
    return tables;
  }

  /** The manifest once pricing's background evaluation has stopped changing it. */
  private Map<String, JsonObject> stableManifest(String shop) throws InterruptedException {
    Map<String, JsonObject> last = manifest(shop);
    for (int i = 0; i < 40; i++) {
      Thread.sleep(250);
      Map<String, JsonObject> now = manifest(shop);
      if (now.equals(last)) return now;
      last = now;
    }
    throw new AssertionError("the manifest kept changing: " + last);
  }

  private List<JsonArray> pages(String shop, String table, int limit) {
    List<JsonArray> out = new ArrayList<>();
    String cursor = null;
    do {
      var t = target.path("/admin/tenant-data/tables/" + table).queryParam("limit", limit);
      if (cursor != null) t = t.queryParam("after", cursor);
      Response r = t.request().header("X-Tenant-Id", shop).header("X-Roles", "OWNER").get();
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      JsonObject page = parse(body).getJsonObject("data");
      out.add(page.getJsonArray("rows"));
      // A page with no next one leaves nextCursor out: JSON-B does not write nulls.
      cursor =
          page.containsKey("nextCursor") && !page.isNull("nextCursor")
              ? page.getString("nextCursor")
              : null;
    } while (cursor != null);
    return out;
  }

  private Response importPage(String shop, String table, JsonArray rows, String role) {
    return post(
        "/admin/tenant-data/tables/" + table,
        Json.createObjectBuilder().add("rows", rows).build().toString(),
        shop,
        role);
  }

  private Response post(String path, String json, String shop, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", shop)
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String shop, String roles) {
    return target.path(path).request().header("X-Tenant-Id", shop).header("X-Roles", roles).get();
  }

  private static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  private static String erasureDue(String eventId, String tenant) {
    return Json.createObjectBuilder()
        .add("eventId", eventId)
        .add("eventType", "TenantDataErasureDue")
        .add("tenantId", tenant)
        .add("occurredAt", java.time.Instant.now().toString())
        .build()
        .toString();
  }

  private static List<String> outbox(String eventType) throws SQLException {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM pricing.outbox WHERE event_type = ? ORDER BY created_at, id")) {
      ps.setString(1, eventType);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static String eventIdOf(String payload) {
    return parse(payload).getString("eventId");
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }
}
