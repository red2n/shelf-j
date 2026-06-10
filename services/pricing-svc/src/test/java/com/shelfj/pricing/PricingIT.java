package com.shelfj.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for pricing-svc: UK VAT rates, price lists, price resolution, tax transactions
 * (POSLog), and MTD VAT return. Kafka/Consul disabled.
 */
@HelidonTest
class PricingIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "pricing");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String V = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String S = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String ORDER_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
  private static final String LINE_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncatePricingTables() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE pricing.promotion_items, pricing.promotions,"
              + " pricing.tax_transactions, pricing.price_list_items, pricing.price_lists,"
              + " pricing.product_vat_categories, pricing.customer_vat_status,"
              + " pricing.vat_rates, pricing.outbox CASCADE");
    }
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String tenant) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request().header("X-Tenant-Id", tenant).get();
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void vatRateCrudAndTenantIsolation() {
    // Create UK standard rate T1 = 20%
    Response r1 =
        post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,"
                + "\"exempt\":false,\"description\":\"UK Standard VAT\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    assertThat(r1.readEntity(String.class), containsString("T1"));

    // Create reduced rate T5 = 5%
    Response r2 =
        post(
            "/vat-rates",
            "{\"code\":\"T5\",\"name\":\"Reduced Rate\",\"rate\":0.05,"
                + "\"exempt\":false,\"description\":\"UK Reduced VAT\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(r2.getStatus(), is(201));

    // Get specific rate
    Response r3 = get("/vat-rates/T1", T);
    assertThat(r3.getStatus(), is(200));
    assertThat(r3.readEntity(String.class), containsString("Standard Rate"));

    // Tenant isolation — other tenant cannot see T1
    Response rIso = get("/vat-rates/T1", "99999999-9999-9999-9999-999999999999");
    assertThat(rIso.getStatus(), is(404));

    // Duplicate code is 409
    Response rDup =
        post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Dup\",\"rate\":0.10,"
                + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(rDup.getStatus(), is(409));
  }

  @Test
  void priceListAndResolution() {
    // Seed VAT rates
    post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,"
            + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        T);

    // Assign VAT code to variant
    post("/product-vat-categories", "{\"variantId\":\"" + V + "\",\"vatCode\":\"T1\"}", T);

    // Create price list in GBP
    Response plR =
        post(
            "/price-lists",
            "{\"name\":\"Standard GBP\",\"channel\":\"ALL\","
                + "\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(plR.getStatus(), is(201));
    String plBody = plR.readEntity(String.class);
    String plId = extractId(plBody);

    // Add price: £10.00 for variant
    Response piR =
        post(
            "/price-lists/" + plId + "/items",
            "{\"variantId\":\"" + V + "\",\"price\":10.00,\"minQty\":1}",
            T);
    assertThat(piR.getStatus(), is(200));

    // Resolve price — should get £10.00 + 20% VAT = £12.00
    Response resR =
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\",\"qty\":1}", T);
    assertThat(resR.getStatus(), is(200));
    String res = resR.readEntity(String.class);
    assertThat(res, containsString("10"));
    assertThat(res, containsString("GBP"));
    assertThat(res, containsString("T1"));

    // Tenant isolation for price resolution
    Response rIso =
        post(
            "/prices/resolve",
            "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\",\"qty\":1}",
            "99999999-9999-9999-9999-999999999999");
    assertThat(rIso.getStatus(), is(404));
  }

  @Test
  void taxTransactionAndVatReturn() {
    // Record a £100 net sale with 20% VAT = £20 VAT, £120 gross
    Response r1 =
        post(
            "/tax-transactions",
            "{\"orderId\":\""
                + ORDER_ID
                + "\","
                + "\"orderLineId\":\""
                + LINE_ID
                + "\","
                + "\"variantId\":\""
                + V
                + "\","
                + "\"storeId\":\""
                + S
                + "\","
                + "\"vatCode\":\"T1\","
                + "\"vatRate\":0.20,"
                + "\"netAmount\":100.00,"
                + "\"vatAmount\":20.00,"
                + "\"grossAmount\":120.00,"
                + "\"exempt\":false,"
                + "\"taxPointDate\":\"2024-04-01T10:00:00Z\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    assertThat(r1.readEntity(String.class), containsString("T1"));

    // List by order
    Response r2 = get("/tax-transactions?orderId=" + ORDER_ID, T);
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("100"));

    // MTD VAT return for Q1 2024
    Response vr = get("/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z", T);
    assertThat(vr.getStatus(), is(200));
    String vrBody = vr.readEntity(String.class);
    // Box 1 = 20.00, Box 6 = 100.00
    assertThat(vrBody, containsString("box1"));
    assertThat(vrBody, containsString("20.00"));
    assertThat(vrBody, containsString("100.00"));

    // Tenant isolation — other tenant's VAT return is zero
    Response vrIso =
        get(
            "/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z",
            "99999999-9999-9999-9999-999999999999");
    assertThat(vrIso.getStatus(), is(200));
    assertThat(vrIso.readEntity(String.class), containsString("0.00"));
  }

  @Test
  void promotionAppliedInPriceResolution() {
    // Seed prerequisites
    post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,"
            + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        T);
    post("/product-vat-categories", "{\"variantId\":\"" + V + "\",\"vatCode\":\"T1\"}", T);
    Response plR =
        post(
            "/price-lists",
            "{\"name\":\"Promo Test\",\"channel\":\"ALL\","
                + "\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    String plId = extractId(plR.readEntity(String.class));
    post(
        "/price-lists/" + plId + "/items",
        "{\"variantId\":\"" + V + "\",\"price\":100.00,\"minQty\":1}",
        T);

    // Create 10% off promotion
    Response promoR =
        post(
            "/promotions",
            "{\"name\":\"Summer Sale\",\"type\":\"PERCENT\",\"value\":10,"
                + "\"channel\":\"ALL\","
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            T);
    assertThat(promoR.getStatus(), is(201));
    String promoId = extractId(promoR.readEntity(String.class));

    // Scope promotion to ALL
    Response piR = post("/promotions/" + promoId + "/items", "{\"scopeType\":\"ALL\"}", T);
    assertThat(piR.getStatus(), is(201));

    // Resolve — expect 10% off: £90 net + 20% VAT = £18 VAT = £108 gross
    Response resR =
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\",\"qty\":1}", T);
    assertThat(resR.getStatus(), is(200));
    String res = resR.readEntity(String.class);
    assertThat(res, containsString("Summer Sale"));
    assertThat(res, containsString("90.00"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }
}
