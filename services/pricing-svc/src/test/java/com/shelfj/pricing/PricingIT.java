package com.shelfj.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
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
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "pricing");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String V = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String S = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String ORDER_ID = "01a090ae-611e-7056-8f30-ecdbb48160eb";
  private static final String LINE_ID = "01a090ae-611e-705c-994c-5daee3fbd033";

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
          "TRUNCATE TABLE pricing.input_tax_transactions, pricing.promotion_redemptions, pricing.promotion_items,"
              + " pricing.promotions,"
              + " pricing.promotion_status_changes, pricing.tax_transactions, pricing.price_list_items, pricing.price_lists,"
              + " pricing.product_vat_categories, pricing.customer_vat_status,"
              + " pricing.vat_rates, pricing.outbox CASCADE");
    }
  }

  private Response post(String path, String json, String tenant) {
    return postAs(path, json, tenant, "OWNER");
  }

  private Response postAs(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
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

  /** A GET carrying roles — the financial reports are gated, so callers must state who they are. */
  private Response getAs(String pathAndQuery, String tenant, String roles) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void priceOverrideRejectsNegativeOriginalPrice() {
    Response r =
        post(
            "/admin/price-overrides",
            "{\"variantId\":\""
                + V
                + "\",\"storeId\":\""
                + S
                + "\",\"originalPrice\":-5.00,\"overridePrice\":10.00,"
                + "\"overrideReason\":\"manager discretion\"}",
            T);
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void priceOverrideAcceptsValidRequest() {
    Response r =
        post(
            "/admin/price-overrides",
            "{\"variantId\":\""
                + V
                + "\",\"storeId\":\""
                + S
                + "\",\"originalPrice\":20.00,\"overridePrice\":10.00,"
                + "\"overrideReason\":\"manager discretion\"}",
            T);
    assertThat(r.getStatus(), is(201));
    assertThat(r.readEntity(String.class), containsString("10.00"));
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
    Response r3 = getAs("/vat-rates/T1", T, "OWNER");
    assertThat(r3.getStatus(), is(200));
    assertThat(r3.readEntity(String.class), containsString("Standard Rate"));

    // Tenant isolation — other tenant cannot see T1
    Response rIso = getAs("/vat-rates/T1", "01a090ae-611e-701d-9d60-a9d7516ed03b", "OWNER");
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

  /** Past the column's size or the rate CHECK the insert failed in Postgres: 500, not 400. */
  @Test
  void vatRateOutsideTheTablesLimitsIsRejectedUpFront() {
    Response longCode =
        post(
            "/vat-rates",
            "{\"code\":\"STANDARD9\",\"name\":\"Standard\",\"rate\":0.20,"
                + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(longCode.getStatus(), is(400));
    assertThat(longCode.readEntity(String.class), containsString("VALIDATION_FAILED"));

    Response percentNotFraction =
        post(
            "/vat-rates",
            "{\"code\":\"T20\",\"name\":\"Twenty\",\"rate\":20,"
                + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(percentNotFraction.getStatus(), is(400));
    assertThat(percentNotFraction.readEntity(String.class), containsString("VALIDATION_FAILED"));
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
            "/admin/price-lists",
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
            "/admin/price-lists/" + plId + "/items",
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
            "01a090ae-611e-701d-9d60-a9d7516ed03b");
    assertThat(rIso.getStatus(), is(404));

    // Batch form: order-svc's checkout resolves every line in one call instead of one per line.
    Response batchR =
        post(
            "/prices/resolve-batch",
            "{\"lines\":["
                + "{\"variantId\":\""
                + V
                + "\",\"channel\":\"ALL\",\"qty\":1},"
                + "{\"variantId\":\""
                + V
                + "\",\"channel\":\"ALL\",\"qty\":2}"
                + "]}",
            T);
    assertThat(batchR.getStatus(), is(200));
    String batchBody = batchR.readEntity(String.class);
    assertThat(batchBody, containsString("\"results\""));
    // both lines resolved (two "unitPrice" entries in the results array)
    assertThat(batchBody.split("\"unitPrice\"", -1).length - 1, is(2));
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
    Response r2 = getAs("/tax-transactions?orderId=" + ORDER_ID, T, "CASHIER");
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("100"));

    // MTD VAT return for Q1 2024
    Response vr =
        getAs("/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z", T, "OWNER");
    assertThat(vr.getStatus(), is(200));
    String vrBody = vr.readEntity(String.class);
    // Box 1 = 20.00, Box 6 = 100.00
    assertThat(vrBody, containsString("box1"));
    assertThat(vrBody, containsString("20.00"));
    assertThat(vrBody, containsString("100.00"));

    // Tenant isolation — other tenant's VAT return is zero
    Response vrIso =
        getAs(
            "/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z",
            "01a090ae-611e-701d-9d60-a9d7516ed03b",
            "OWNER");
    assertThat(vrIso.getStatus(), is(200));
    assertThat(vrIso.readEntity(String.class), containsString("0.00"));
  }

  // ── the tax summary report, and the gate the VAT return never had ───────────

  private Response recordTax(
      String order,
      String store,
      String code,
      String rate,
      String net,
      String vat,
      String gross,
      boolean exempt,
      String taxPoint) {
    return post(
        "/tax-transactions",
        "{\"orderId\":\""
            + order
            + "\",\"orderLineId\":\""
            + com.shelfj.ids.Ids.newId()
            + "\",\"variantId\":\""
            + V
            + "\",\"storeId\":\""
            + store
            + "\",\"vatCode\":\""
            + code
            + "\",\"vatRate\":"
            + rate
            + ",\"netAmount\":"
            + net
            + ",\"vatAmount\":"
            + vat
            + ",\"grossAmount\":"
            + gross
            + ",\"exempt\":"
            + exempt
            + ",\"taxPointDate\":\""
            + taxPoint
            + "\"}",
        T);
  }

  /**
   * The report's whole claim is that it reconciles: its totals must equal the VAT return computed
   * over the same rows and the same period, or it is worse than useless to the person filing.
   */
  @Test
  void taxSummaryGroupsByCodeAndReconcilesWithTheVatReturn() {
    // £100 net + £20 VAT standard-rated, twice; plus a £50 exempt supply carrying no VAT.
    recordTax(
        ORDER_ID, S, "T1", "0.20", "100.00", "20.00", "120.00", false, "2024-04-01T10:00:00Z");
    recordTax(
        ORDER_ID, S, "T1", "0.20", "100.00", "20.00", "120.00", false, "2024-05-02T10:00:00Z");
    recordTax(ORDER_ID, S, "T0", "0.00", "50.00", "0.00", "50.00", true, "2024-04-03T10:00:00Z");

    String period = "from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z";
    Response r = getAs("/admin/reports/tax-summary?" + period, T, "OWNER");
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);

    // Grouped by code by default, and the exempt supply is its own line rather than being folded
    // into the standard-rated one.
    assertThat(body, containsString("\"groupKey\":\"T1\""));
    assertThat(body, containsString("\"groupKey\":\"T0\""));
    assertThat(body, containsString("\"exempt\":true"));

    // Totals: net 100+100+50 = 250.00, output VAT 20+20 = 40.00 (the exempt line adds none).
    assertThat(body, containsString("\"netAmount\":250.00"));
    assertThat(body, containsString("\"outputVat\":40.00"));
    assertThat(body, containsString("\"transactions\":3"));

    // The reconciliation, asserted rather than asserted-about: Box 6 is total net, Box 1 is
    // output VAT, over the same period.
    String vat = getAs("/vat-return?" + period, T, "OWNER").readEntity(String.class);
    assertThat(vat, containsString("\"box6\":250.00"));
    assertThat(vat, containsString("\"box1\":40.00"));
  }

  /** MONTH grouping is what shows a rate change, or a supply landing in the wrong VAT quarter. */
  @Test
  void taxSummaryCanGroupByMonthAndByStore() {
    String otherStore = "01a090ae-611e-700c-a049-f00caee8e6c5";
    recordTax(
        ORDER_ID, S, "T1", "0.20", "100.00", "20.00", "120.00", false, "2024-04-01T10:00:00Z");
    recordTax(
        ORDER_ID,
        otherStore,
        "T1",
        "0.20",
        "10.00",
        "2.00",
        "12.00",
        false,
        "2024-05-02T10:00:00Z");

    String period = "from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z";
    String byMonth =
        getAs("/admin/reports/tax-summary?" + period + "&groupBy=MONTH", T, "OWNER")
            .readEntity(String.class);
    assertThat(byMonth, containsString("\"groupKey\":\"2024-04\""));
    assertThat(byMonth, containsString("\"groupKey\":\"2024-05\""));

    String byStore =
        getAs("/admin/reports/tax-summary?" + period + "&groupBy=STORE", T, "OWNER")
            .readEntity(String.class);
    assertThat(byStore, containsString(S));
    assertThat(byStore, containsString(otherStore));

    // storeId narrows to one site; the other store's £2 must not appear in the total.
    String oneStore =
        getAs("/admin/reports/tax-summary?" + period + "&storeId=" + S, T, "OWNER")
            .readEntity(String.class);
    assertThat(oneStore, containsString("\"outputVat\":20.00"));

    // Unknown groupBy is the caller's mistake, and storeId must be a UUID.
    assertThat(
        getAs("/admin/reports/tax-summary?" + period + "&groupBy=SUPPLIER", T, "OWNER").getStatus(),
        is(400));
    assertThat(
        getAs("/admin/reports/tax-summary?" + period + "&storeId=nope", T, "OWNER").getStatus(),
        is(400));
  }

  /**
   * Both of these served a tenant's tax position to any authenticated caller, because neither path
   * sits under /admin/ and so AdminAuthorizationFilter never looked at them. A signed-in storefront
   * customer could read the VAT return.
   */
  @Test
  void theFinancialReadsAreNoLongerOpenToAnyCaller() {
    String period = "from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z";

    assertThat(getAs("/vat-return?" + period, T, "CUSTOMER").getStatus(), is(403));
    assertThat(getAs("/vat-return?" + period, T, "CASHIER").getStatus(), is(403));
    assertThat(get("/vat-return?" + period, T).getStatus(), is(403));
    assertThat(getAs("/vat-return?" + period, T, "MANAGER").getStatus(), is(200));

    // The tax journal stays reachable by staff — a cashier querying a receipt is legitimate — but
    // not by a customer who happens to know an order id.
    assertThat(getAs("/tax-transactions?orderId=" + ORDER_ID, T, "CUSTOMER").getStatus(), is(403));
    assertThat(getAs("/tax-transactions?orderId=" + ORDER_ID, T, "CASHIER").getStatus(), is(200));

    // The new report is gated by its path, so it needs no check of its own.
    assertThat(getAs("/admin/reports/tax-summary?" + period, T, "CASHIER").getStatus(), is(403));
    assertThat(getAs("/admin/reports/tax-summary?" + period, T, "OWNER").getStatus(), is(200));
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
            "/admin/price-lists",
            "{\"name\":\"Promo Test\",\"channel\":\"ALL\","
                + "\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    String plId = extractId(plR.readEntity(String.class));
    post(
        "/admin/price-lists/" + plId + "/items",
        "{\"variantId\":\"" + V + "\",\"price\":100.00,\"minQty\":1}",
        T);

    // Create 10% off promotion
    Response promoR =
        post(
            "/admin/promotions",
            "{\"name\":\"Summer Sale\",\"type\":\"PERCENT\",\"value\":10,"
                + "\"channel\":\"ALL\","
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            T);
    assertThat(promoR.getStatus(), is(201));
    String promoId = extractId(promoR.readEntity(String.class));

    // Scope promotion to ALL
    Response piR = post("/admin/promotions/" + promoId + "/items", "{\"scopeType\":\"ALL\"}", T);
    assertThat(piR.getStatus(), is(201));

    // Resolve — expect 10% off: £90 net + 20% VAT = £18 VAT = £108 gross
    Response resR =
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\",\"qty\":1}", T);
    assertThat(resR.getStatus(), is(200));
    String res = resR.readEntity(String.class);
    assertThat(res, containsString("Summer Sale"));
    assertThat(res, containsString("90.00"));
  }

  // ── Basket quoting: the rules the old engine could not express ─────────────

  /** Seeds a VAT rate, a price list and one priced variant. Returns nothing; the ids are fixed. */
  private void seedPricedVariant(String variantId, String price) {
    post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,"
            + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        T);
    post("/product-vat-categories", "{\"variantId\":\"" + variantId + "\",\"vatCode\":\"T1\"}", T);
    Response plR =
        post(
            "/admin/price-lists",
            "{\"name\":\"Basket Test\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    String plId = extractId(plR.readEntity(String.class));
    post(
        "/admin/price-lists/" + plId + "/items",
        "{\"variantId\":\"" + variantId + "\",\"price\":" + price + ",\"minQty\":1}",
        T);
  }

  private String createPromotion(String json) {
    Response r = post("/admin/promotions", json, T);
    assertThat(r.getStatus() + " " + json, r.getStatus(), is(201));
    String id = extractId(r.readEntity(String.class));
    assertThat(
        post("/admin/promotions/" + id + "/items", "{\"scopeType\":\"ALL\"}", T).getStatus(),
        is(201));
    return id;
  }

  private String quote(String json) {
    Response r = post("/prices/quote", json, T);
    assertThat(r.getStatus(), is(200));
    return r.readEntity(String.class);
  }

  /**
   * The whole reason for the rebuild: a rule that needs the order total. The old engine priced each
   * line with an independent call, so a spend threshold had no basket to be measured against and
   * min_order_amount was never read at all.
   */
  @Test
  void aSpendThresholdIsMeasuredAgainstTheWholeBasket() {
    seedPricedVariant(V, "40.00");
    createPromotion(
        "{\"name\":\"£5 off over £100\",\"type\":\"SPEND_THRESHOLD\",\"value\":5,"
            + "\"minOrderAmount\":100,\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    // Two at 40 = 80: under the threshold, nothing comes off.
    String under = quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":2}]}");
    assertThat(under, containsString("\"totalDiscount\":0"));

    // Three at 40 = 120: the same basket, one item larger, now clears it.
    String over = quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":3}]}");
    assertThat(over, containsString("\"totalDiscount\":5.00"));
    assertThat(over, containsString("£5 off over £100"));
    // 120 − 5 = 115, VAT 23.00, total 138.00.
    assertThat(over, containsString("\"vatAmount\":23.00"));
    assertThat(over, containsString("\"total\":138.00"));
  }

  /**
   * Two basket lines of the same variant must not each be charged the whole discount.
   *
   * <p>Both {@code BasketLine} and {@code LineDiscount} are keyed by variantId, so the engine
   * returns one combined figure for a variant however many lines carry it. Folding that figure back
   * with {@code getOrDefault(variantId)} applied it once per line: the response's own lines then
   * contradicted its {@code subtotal} and {@code totalDiscount}, and order-svc — which derives the
   * stored unit price from {@code lineTotal} minus the discount — undercharged by the difference.
   */
  @Test
  void aVariantOnTwoBasketLinesSplitsItsDiscountRatherThanDoublingIt() {
    seedPricedVariant(V, "50.00");
    createPromotion(
        "{\"name\":\"10% off everything\",\"type\":\"PERCENT\",\"value\":10,"
            + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    // The same variant twice: 2 × 50 and 1 × 50. Subtotal 150, so 10% is 15.00 in total.
    String body =
        quote(
            "{\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2},{\"variantId\":\""
                + V
                + "\",\"qty\":1}]}");

    assertThat(body, containsString("\"subtotal\":150.00"));
    assertThat(body, containsString("\"totalDiscount\":15.00"));

    // The lines must add up to the totals above. Before the fix each line carried the full 15.00 —
    // 30.00 across the basket against a stated totalDiscount of 15.00.
    assertThat(sumOf(body, "\"discount\":"), comparesEqualTo(new java.math.BigDecimal("15.00")));
    assertThat(sumOf(body, "\"netTotal\":"), comparesEqualTo(new java.math.BigDecimal("135.00")));
  }

  /** Sums every occurrence of a numeric JSON field in the body. */
  private static java.math.BigDecimal sumOf(String json, String field) {
    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
    for (int i = json.indexOf(field); i >= 0; i = json.indexOf(field, i + 1)) {
      int start = i + field.length();
      int end = start;
      while (end < json.length()
          && (Character.isDigit(json.charAt(end))
              || json.charAt(end) == '.'
              || json.charAt(end) == '-')) {
        end++;
      }
      if (end > start) total = total.add(new java.math.BigDecimal(json.substring(start, end)));
    }
    return total;
  }

  // ── SJ-D33 / SJ-D36: a promotion that can be stopped, by someone entitled to ──

  /**
   * The defect, and the fix. Before this, <code>promotions.active</code> had no writer of any kind:
   * a promotion created without an end date ran forever and could only be stopped by reaching into
   * the database.
   */
  @Test
  void aRunningPromotionCanBeStoppedAndStartedAgain() {
    seedPricedVariant(V, "100.00");
    String id =
        createPromotion(
            "{\"name\":\"Runaway\",\"type\":\"BASKET_PERCENT\",\"value\":50,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    // It is running: half off a £100 basket.
    assertThat(
        quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}"),
        containsString("\"totalDiscount\":50.00"));

    Response stop =
        post(
            "/admin/promotions/" + id + "/deactivate",
            "{\"reason\":\"priced wrong — 50% was meant to be 5%\"}",
            T);
    assertThat(stop.getStatus(), is(200));

    // And now it is not. This assertion is the whole defect.
    assertThat(
        quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}"),
        containsString("\"totalDiscount\":0"));

    assertThat(
        post(
                "/admin/promotions/" + id + "/activate",
                "{\"reason\":\"repriced and re-approved\"}",
                T)
            .getStatus(),
        is(200));
    assertThat(
        quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}"),
        containsString("\"totalDiscount\":50.00"));
  }

  /** Both directions need a reason, and the trail keeps every switch. */
  @Test
  void everySwitchIsRecordedWithAReason() {
    String id =
        createPromotion(
            "{\"name\":\"Audited\",\"type\":\"BASKET_FLAT\",\"value\":5,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    // A switch with no stated reason is a discount that vanished with nobody accountable.
    assertThat(post("/admin/promotions/" + id + "/deactivate", "{}", T).getStatus(), is(400));

    post("/admin/promotions/" + id + "/deactivate", "{\"reason\":\"stopped\"}", T);
    post("/admin/promotions/" + id + "/activate", "{\"reason\":\"restarted\"}", T);

    String hist =
        getAs("/admin/promotions/" + id + "/status-history", T, "OWNER").readEntity(String.class);
    assertThat(hist, containsString("stopped"));
    assertThat(hist, containsString("restarted"));
    // Append-only: restarting does not erase the record of it having been stopped.
    assertThat(hist, containsString("\"active\":false"));
    assertThat(hist, containsString("\"active\":true"));
  }

  /** Stopping something already stopped is a conflict, not a silent success. */
  @Test
  void switchingToTheStateItIsAlreadyInIsRefused() {
    String id =
        createPromotion(
            "{\"name\":\"Idempotent?\",\"type\":\"BASKET_FLAT\",\"value\":1,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    assertThat(
        post("/admin/promotions/" + id + "/deactivate", "{\"reason\":\"a\"}", T).getStatus(),
        is(200));
    Response again = post("/admin/promotions/" + id + "/deactivate", "{\"reason\":\"b\"}", T);
    assertThat(again.getStatus(), is(409));
    // Two people stopping the same runaway promotion must not both be told they did it, and the
    // trail must not gain a row for a switch that never moved.
    assertThat(again.readEntity(String.class), containsString("PRICING_ALREADY_IN_STATE"));
  }

  /** SJ-D36: creating a promotion is money leaving the business, and was open to any staff role. */
  @Test
  void aCashierCannotCreateOrStopAPromotion() {
    assertThat(
        postAs(
                "/admin/promotions",
                "{\"name\":\"100% off\",\"type\":\"BASKET_PERCENT\",\"value\":100,"
                    + "\"startsAt\":\"2020-01-01T00:00:00Z\"}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));

    String id =
        createPromotion(
            "{\"name\":\"Manager's\",\"type\":\"BASKET_FLAT\",\"value\":2,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    assertThat(
        postAs("/admin/promotions/" + id + "/deactivate", "{\"reason\":\"x\"}", T, "CASHIER")
            .getStatus(),
        is(403));

    // The storefront read stays open — a shopper must still be able to see the offers.
    assertThat(getAs("/promotions", T, "CUSTOMER").getStatus(), is(200));
  }

  // ── SJ-D37 / SJ-D38: the same two defects, on the thing that IS the price ──

  /**
   * A price list nobody could switch off.
   *
   * <p>SJ-D33 and SJ-D36 were reported against promotions. Price lists carried both defects in
   * identical form and neither was reported: {@code price_lists.active} has been {@code NOT NULL
   * DEFAULT TRUE} since V1 and the resolve query filters on it, so it decides what customers are
   * charged — and nothing in the product ever wrote it. A promotion discounts a price; a price list
   * <em>is</em> the price, so this is the more expensive of the two.
   */
  @Test
  void aLivePriceListCanBeStoppedAndTheResolvedPriceChanges() {
    post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,"
            + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        T);
    post("/product-vat-categories", "{\"variantId\":\"" + V + "\",\"vatCode\":\"T1\"}", T);
    String plId = createPriceList(T, "Mispriced", "GBP");
    post(
        "/admin/price-lists/" + plId + "/items",
        "{\"variantId\":\"" + V + "\",\"price\":1.00,\"minQty\":1}",
        T);

    // A £100 item listed at £1. Until this fix, that stood until someone edited the database.
    Response live = post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", T);
    assertThat(live.getStatus(), is(200));
    assertThat(live.readEntity(String.class), containsString("1.00"));

    assertThat(
        post(
                "/admin/price-lists/" + plId + "/deactivate",
                "{\"reason\":\"decimal slipped — £1.00 should have been £100.00\"}",
                T)
            .getStatus(),
        is(200));

    // Now nothing prices it, which is the correct answer: refusing to sell beats selling at a
    // price nobody agreed. This assertion is the whole defect.
    Response stopped =
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", T);
    assertThat(stopped.getStatus(), is(404));
    assertThat(stopped.readEntity(String.class), containsString("PRICING_PRICE_NOT_FOUND"));

    assertThat(
        post("/admin/price-lists/" + plId + "/activate", "{\"reason\":\"corrected\"}", T)
            .getStatus(),
        is(200));
    assertThat(
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", T).getStatus(),
        is(200));
  }

  /**
   * Five markets, five currencies, five tenants — the switch is scoped to one of them.
   *
   * <p>Stopping the Japanese price list must not touch the British one, and a whole-yen price must
   * survive the round trip without gaining decimals it does not have. JPY has no minor unit (ISO
   * 4217 exponent 0), the other four have two, so this also proves the switch does not care about
   * scale.
   */
  @Test
  void stoppingOneMarketsPricesLeavesTheOthersSelling() {
    record Market(String tenant, String currency, String price) {}
    var markets =
        java.util.List.of(
            new Market("01a090ae-611e-700b-bde4-50df0324c37c", "USD", "9.99"),
            new Market("01a090ae-611e-700f-b645-a14095230b77", "GBP", "8.50"),
            new Market("01a090ae-611e-7011-ae7d-1bd68c966ff6", "CNY", "69.00"),
            new Market("01a090ae-611e-7014-8cd5-baf0862fa319", "JPY", "1234"),
            new Market("01a090ae-611e-7019-ba7e-5901486ca70a", "INR", "849.00"));

    var lists = new java.util.LinkedHashMap<String, String>();
    for (Market m : markets) {
      post(
          "/vat-rates",
          "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,"
              + "\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
          m.tenant());
      post(
          "/product-vat-categories",
          "{\"variantId\":\"" + V + "\",\"vatCode\":\"T1\"}",
          m.tenant());
      String id = createPriceList(m.tenant(), m.currency() + " list", m.currency());
      post(
          "/admin/price-lists/" + id + "/items",
          "{\"variantId\":\"" + V + "\",\"price\":" + m.price() + ",\"minQty\":1}",
          m.tenant());
      lists.put(m.tenant(), id);
    }

    String jp = "01a090ae-611e-7014-8cd5-baf0862fa319";
    // Whole yen, and no invented sub-unit on the way out.
    String yen =
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", jp)
            .readEntity(String.class);
    assertThat(yen, containsString("1234"));
    assertThat(yen, containsString("JPY"));

    assertThat(
        post(
                "/admin/price-lists/" + lists.get(jp) + "/deactivate",
                "{\"reason\":\"supplier withdrew the Japanese line\"}",
                jp)
            .getStatus(),
        is(200));

    assertThat(
        post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", jp)
            .getStatus(),
        is(404));

    // Every other market is still selling, at its own currency and its own scale.
    for (Market m : markets) {
      if (m.tenant().equals(jp)) {
        continue;
      }
      Response r =
          post("/prices/resolve", "{\"variantId\":\"" + V + "\",\"channel\":\"ALL\"}", m.tenant());
      assertThat(m.currency() + " should still price", r.getStatus(), is(200));
      String body = r.readEntity(String.class);
      assertThat(body, containsString(m.currency()));
      assertThat(body, containsString(m.price()));
    }

    // And one tenant cannot reach into another's switch, even knowing the id.
    assertThat(
        post(
                "/admin/price-lists/" + lists.get(jp) + "/activate",
                "{\"reason\":\"not mine to restart\"}",
                "01a090ae-611e-700f-b645-a14095230b77")
            .getStatus(),
        is(404));
  }

  /** SJ-D37: proved against the running stack — a CASHIER token created a price list, 201. */
  @Test
  void aCashierCannotSetPricesOrStopAPriceList() {
    assertThat(
        postAs(
                "/admin/price-lists",
                "{\"name\":\"Cashier's own prices\",\"channel\":\"ALL\","
                    + "\"currency\":\"GBP\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));

    String plId = createPriceList(T, "Managed", "GBP");
    assertThat(
        postAs(
                "/admin/price-lists/" + plId + "/items",
                "{\"variantId\":\"" + V + "\",\"price\":0.01,\"minQty\":1}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        postAs(
                "/admin/price-lists/" + plId + "/items/batch",
                "{\"items\":[{\"variantId\":\"" + V + "\",\"price\":0.01,\"minQty\":1}]}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        postAs("/admin/price-lists/" + plId + "/deactivate", "{\"reason\":\"x\"}", T, "CASHIER")
            .getStatus(),
        is(403));

    // The till still has to be able to read prices, or it cannot sell anything.
    assertThat(getAs("/price-lists", T, "CASHIER").getStatus(), is(200));
    assertThat(getAs("/price-lists/" + plId + "/items", T, "CASHIER").getStatus(), is(200));
  }

  /** One trail serves both subjects, and must not blur them. */
  @Test
  void aPriceListsHistoryDoesNotShowThePromotionsSwitches() {
    String promoId =
        createPromotion(
            "{\"name\":\"Unrelated\",\"type\":\"BASKET_FLAT\",\"value\":1,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    post("/admin/promotions/" + promoId + "/deactivate", "{\"reason\":\"promotion reason\"}", T);

    String plId = createPriceList(T, "Separate", "GBP");
    post("/admin/price-lists/" + plId + "/deactivate", "{\"reason\":\"price list reason\"}", T);

    String plHist =
        getAs("/admin/price-lists/" + plId + "/status-history", T, "OWNER")
            .readEntity(String.class);
    assertThat(plHist, containsString("price list reason"));
    assertThat(plHist, not(containsString("promotion reason")));

    String promoHist =
        getAs("/admin/promotions/" + promoId + "/status-history", T, "OWNER")
            .readEntity(String.class);
    assertThat(promoHist, containsString("promotion reason"));
    assertThat(promoHist, not(containsString("price list reason")));
  }

  private String createPriceList(String tenant, String name, String currency) {
    Response r =
        post(
            "/admin/price-lists",
            "{\"name\":\""
                + name
                + "\",\"channel\":\"ALL\",\"currency\":\""
                + currency
                + "\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            tenant);
    assertThat(r.getStatus(), is(201));
    return extractId(r.readEntity(String.class));
  }

  /** A coupon does nothing until it is presented, and is matched case-insensitively. */
  @Test
  void aCouponAppliesOnlyWhenPresented() {
    seedPricedVariant(V, "100.00");
    createPromotion(
        "{\"name\":\"Welcome\",\"type\":\"BASKET_PERCENT\",\"value\":10,"
            + "\"couponCode\":\"SAVE10\",\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    String without = quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}");
    assertThat(without, containsString("\"totalDiscount\":0"));

    String with =
        quote(
            "{\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}],"
                + "\"couponCodes\":[\"save10\"]}");
    assertThat(with, containsString("\"totalDiscount\":10.00"));
  }

  /**
   * A code that does nothing has to say why — "nothing happened" is what generates support calls.
   */
  @Test
  void aRejectedCouponComesBackWithAReason() {
    seedPricedVariant(V, "100.00");
    String body =
        quote(
            "{\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}],"
                + "\"couponCodes\":[\"NOPE\"]}");
    assertThat(body, containsString("NO_SUCH_COUPON"));
  }

  /** Two coupon promotions cannot share a code, or which one applied would be an accident again. */
  @Test
  void couponCodesAreUniquePerTenantCaseInsensitively() {
    createPromotion(
        "{\"name\":\"First\",\"type\":\"BASKET_FLAT\",\"value\":5,"
            + "\"couponCode\":\"DUPE\",\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    Response second =
        post(
            "/admin/promotions",
            "{\"name\":\"Second\",\"type\":\"BASKET_FLAT\",\"value\":9,"
                + "\"couponCode\":\"dupe\",\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            T);
    assertThat(second.getStatus(), is(409));
  }

  /**
   * A half-configured BOGO would apply to every basket and discount nothing — the exact shape of
   * defect this rebuild exists to end, so it is refused at both the service and the database.
   */
  @Test
  void anIncompleteBogoIsRefused() {
    Response r =
        post(
            "/admin/promotions",
            "{\"name\":\"Half a BOGO\",\"type\":\"BOGO\",\"value\":1,"
                + "\"buyQty\":2,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRICING_INCOMPLETE_BOGO"));
  }

  /** SPEND_THRESHOLD without a threshold would discount every basket. */
  @Test
  void aThresholdPromotionWithoutAThresholdIsRefused() {
    Response r =
        post(
            "/admin/promotions",
            "{\"name\":\"No threshold\",\"type\":\"SPEND_THRESHOLD\",\"value\":5,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRICING_MISSING_THRESHOLD"));
  }

  /**
   * CATEGORY scope was in the CHECK constraint, the domain constants, the request schema and the
   * API guide, and the matching query handled only ALL and VARIANT — so it was accepted, stored,
   * and never fired. Refusing it says so where the mistake is made.
   */
  @Test
  void aCategoryScopeIsRefusedRatherThanSilentlyIgnored() {
    String id =
        createPromotion(
            "{\"name\":\"Category test\",\"type\":\"PERCENT\",\"value\":10,"
                + "\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    Response r =
        post(
            "/admin/promotions/" + id + "/items",
            "{\"scopeType\":\"CATEGORY\",\"scopeId\":\"" + S + "\"}",
            T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRICING_CATEGORY_SCOPE_UNSUPPORTED"));
  }

  /**
   * promotions.store_id has been stored since V1 and filtered nowhere, so a promotion created for
   * one shop ran in every shop of the tenant.
   */
  @Test
  void aStoreScopedPromotionDoesNotApplyInAnotherStore() {
    seedPricedVariant(V, "100.00");
    createPromotion(
        "{\"name\":\"Leeds only\",\"type\":\"BASKET_PERCENT\",\"value\":10,"
            + "\"storeId\":\""
            + S
            + "\",\"startsAt\":\"2020-01-01T00:00:00Z\"}");

    String inStore =
        quote("{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}],\"storeId\":\"" + S + "\"}");
    assertThat(inStore, containsString("\"totalDiscount\":10.00"));

    String otherStore =
        quote(
            "{\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}],"
                + "\"storeId\":\"01a090ae-611e-700c-a049-f00caee8e6c5\"}");
    assertThat(otherStore, containsString("\"totalDiscount\":0"));
  }

  @Test
  void priceListsAreCursorPaginated() {
    for (int i = 1; i <= 5; i++) {
      Response r =
          post(
              "/admin/price-lists",
              "{\"name\":\"List "
                  + i
                  + "\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                  + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
              T);
      assertThat(r.getStatus(), is(201));
    }

    // Walk with limit=2: pages of 2,2,1 and every list seen exactly once.
    java.util.Set<String> seen = new java.util.HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      String path = "/price-lists?limit=2" + (cursor == null ? "" : "&after=" + cursor);
      String body = getAs(path, T, "OWNER").readEntity(String.class);
      pages++;
      for (int i = 1; i <= 5; i++) {
        String name = "\"name\":\"List " + i + "\"";
        if (body.contains(name)) {
          assertThat("price list " + i + " served twice", seen.add(name), is(true));
        }
      }
      int c = body.indexOf("\"nextCursor\":\"");
      cursor = c < 0 ? null : body.substring(c + 14, body.indexOf('"', c + 14));
    } while (cursor != null);
    assertThat(pages, is(3));
    assertThat(seen.size(), is(5));
  }

  @Test
  void batchUpsertItemsRejectsAnInvalidItemButStillUpsertsTheRest() {
    Response plR =
        post(
            "/admin/price-lists",
            "{\"name\":\"Batch Test\",\"channel\":\"ALL\","
                + "\"currency\":\"GBP\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(plR.getStatus(), is(201));
    String plId = extractId(plR.readEntity(String.class));

    String goodVariant = com.shelfj.ids.Ids.newId().toString();
    String badVariant = com.shelfj.ids.Ids.newId().toString();
    // One valid item (price 10.00) and one violating @Positive price (-5.00) — the endpoint's
    // contract is "never 4xx on partial failure", so this must stay 200 with the bad item
    // reported in errors and NOT counted as upserted (previously it silently succeeded since
    // nothing validated items inside the batch loop).
    String body =
        "{\"items\":["
            + "{\"variantId\":\""
            + goodVariant
            + "\",\"price\":10.00,\"minQty\":1},"
            + "{\"variantId\":\""
            + badVariant
            + "\",\"price\":-5.00,\"minQty\":1}"
            + "]}";
    Response r = post("/admin/price-lists/" + plId + "/items/batch", body, T);
    assertThat(r.getStatus(), is(200));
    String result = r.readEntity(String.class);
    assertThat(result, containsString("\"upserted\":1"));
    assertThat(result, containsString(badVariant));
  }

  @Test
  void customerVatStatusReadRequiresAStaffRole() {
    String customerId = "01a090ae-611e-7070-9b99-4c0448c39abf";
    Response created =
        post(
            "/customer-vat-status",
            "{\"customerId\":\""
                + customerId
                + "\",\"vatNumber\":\"GB123456789\",\"vatRegistered\":true,"
                + "\"reverseChargeEligible\":false,\"countryCode\":\"GB\"}",
            T);
    assertThat(created.getStatus(), is(200));

    // No role at all (only X-Tenant-Id) — not covered by the write-only default-deny filter, so
    // this read needs its own gate.
    assertThat(get("/customer-vat-status/" + customerId, T).getStatus(), is(403));

    // A staff role can read it.
    Response asStaff =
        target
            .path("/customer-vat-status/" + customerId)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get();
    assertThat(asStaff.getStatus(), is(200));
    assertThat(asStaff.readEntity(String.class), containsString("GB123456789"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  @Test
  void vatReturnSaysOnItsFaceWhichBoxesAreRealAndThatItIsNotFitToFile() {
    // SJ-D39: nine boxes in the shape of an MTD return, five of them real. The guides said so; the
    // return must say so itself before anyone files from it.
    Response vr =
        getAs("/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z", T, "OWNER");
    assertThat(vr.getStatus(), is(200));
    String body = vr.readEntity(String.class);
    assertThat(body, containsString("\"computedBoxes\":[1,3,4,5,6,7]"));
    assertThat(body, containsString("\"notComputedBoxes\":[2,8,9]"));
    assertThat(body, containsString("\"fitToFile\":true"));
    assertThat(body, containsString("Northern Ireland"));
  }

  @Inject com.shelfj.pricing.messaging.SupplierInvoiceEventHandler invoiceEvents;

  private static String invoiceEvent(
      String eventId, String tenant, String vat, String net, String date) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"SupplierInvoiceCaptured\",\"tenantId\":\""
        + tenant
        + "\",\"invoiceId\":\""
        + eventId
        + "\",\"poId\":\""
        + com.shelfj.ids.Ids.newId()
        + "\",\"supplierId\":\""
        + com.shelfj.ids.Ids.newId()
        + "\",\"invoiceNumber\":\"INV-1\",\"invoiceDate\":\""
        + date
        + "\",\"currency\":\"GBP\",\"netAmount\":"
        + net
        + ",\"vatAmount\":"
        + vat
        + ",\"grossAmount\":"
        + new java.math.BigDecimal(net).add(new java.math.BigDecimal(vat)).toPlainString()
        + ",\"status\":\"MATCHED\"}";
  }

  @Test
  void boxFourComesFromSupplierInvoicesOnceEach() {
    // SJ-D39 closed: a captured supplier invoice reaches the return as input VAT, by invoice date.
    String e1 = com.shelfj.ids.Ids.newId().toString();
    assertThat(
        invoiceEvents.handle(invoiceEvent(e1, T, "30.00", "150.00", "2024-05-10")), is(true));
    // Redelivered: the same event id records nothing twice.
    assertThat(
        invoiceEvents.handle(invoiceEvent(e1, T, "30.00", "150.00", "2024-05-10")), is(false));
    // Another tenant's invoice is another tenant's box 4.
    String other = com.shelfj.ids.Ids.newId().toString();
    assertThat(
        invoiceEvents.handle(
            invoiceEvent(
                com.shelfj.ids.Ids.newId().toString(), other, "99.00", "495.00", "2024-05-11")),
        is(true));
    // Outside the period: a Q2 return does not include a July invoice.
    assertThat(
        invoiceEvents.handle(
            invoiceEvent(com.shelfj.ids.Ids.newId().toString(), T, "7.00", "35.00", "2024-07-02")),
        is(true));
    // Malformed, or not this event: skipped, not thrown, not recorded.
    assertThat(
        invoiceEvents.handle(
            "{\"eventType\":\"SupplierInvoiceCaptured\",\"tenantId\":\"" + T + "\"}"),
        is(false));
    assertThat(invoiceEvents.handle("{\"eventType\":\"SomethingElse\"}"), is(false));

    Response vr =
        getAs("/vat-return?from=2024-04-01T00:00:00Z&to=2024-07-01T00:00:00Z", T, "OWNER");
    assertThat(vr.getStatus(), is(200));
    String body = vr.readEntity(String.class);
    assertThat(body, containsString("\"box4\":30.00"));
    assertThat(body, containsString("\"box7\":150.00"));
    // No output VAT was recorded in this test, so box 5 is |0 - 30| = 30.00: a reclaim.
    assertThat(body, containsString("\"box5\":30.00"));
    assertThat(body, containsString("\"fitToFile\":true"));
  }
}
