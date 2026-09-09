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
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
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
          "TRUNCATE TABLE pricing.promotion_redemptions, pricing.promotion_items,"
              + " pricing.promotions,"
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
        .header("X-Roles", "OWNER")
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
    Response rIso = getAs("/vat-rates/T1", "99999999-9999-9999-9999-999999999999", "OWNER");
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
            "99999999-9999-9999-9999-999999999999",
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
            + java.util.UUID.randomUUID()
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
    String otherStore = "11111111-2222-3333-4444-555555555555";
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
            "/price-lists",
            "{\"name\":\"Basket Test\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    String plId = extractId(plR.readEntity(String.class));
    post(
        "/price-lists/" + plId + "/items",
        "{\"variantId\":\"" + variantId + "\",\"price\":" + price + ",\"minQty\":1}",
        T);
  }

  private String createPromotion(String json) {
    Response r = post("/promotions", json, T);
    assertThat(r.getStatus() + " " + json, r.getStatus(), is(201));
    String id = extractId(r.readEntity(String.class));
    assertThat(
        post("/promotions/" + id + "/items", "{\"scopeType\":\"ALL\"}", T).getStatus(), is(201));
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
            "/promotions",
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
            "/promotions",
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
            "/promotions",
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
            "/promotions/" + id + "/items",
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
                + "\"storeId\":\"11111111-2222-3333-4444-555555555555\"}");
    assertThat(otherStore, containsString("\"totalDiscount\":0"));
  }

  @Test
  void priceListsAreCursorPaginated() {
    for (int i = 1; i <= 5; i++) {
      Response r =
          post(
              "/price-lists",
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
            "/price-lists",
            "{\"name\":\"Batch Test\",\"channel\":\"ALL\","
                + "\"currency\":\"GBP\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T);
    assertThat(plR.getStatus(), is(201));
    String plId = extractId(plR.readEntity(String.class));

    String goodVariant = java.util.UUID.randomUUID().toString();
    String badVariant = java.util.UUID.randomUUID().toString();
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
    Response r = post("/price-lists/" + plId + "/items/batch", body, T);
    assertThat(r.getStatus(), is(200));
    String result = r.readEntity(String.class);
    assertThat(result, containsString("\"upserted\":1"));
    assertThat(result, containsString(badVariant));
  }

  @Test
  void customerVatStatusReadRequiresAStaffRole() {
    String customerId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
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
}
