package com.shelfj.purchase;

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
 * Integration tests for purchase-svc: suppliers, purchase orders, GRN, intercompany invoicing (Gap
 * #20), FRS 102 nominal ledger, and BACS 30-day payment terms. Kafka/Consul disabled.
 */
@HelidonTest
class PurchaseIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "purchase");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String T2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String STORE_A = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String STORE_B = "dddddddd-dddd-dddd-dddd-dddddddddddd";
  private static final String VARIANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE purchase.nominal_ledger_entries, purchase.intercompany_invoices,"
              + " purchase.goods_receipt_lines, purchase.goods_receipts,"
              + " purchase.purchase_order_lines, purchase.purchase_orders,"
              + " purchase.suppliers, purchase.outbox CASCADE");
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

  // ── Gap #20 Test 1: Supplier CRUD + tenant isolation ─────────────────────────

  @Test
  void supplierCrudAndTenantIsolation() {
    // Create supplier (UK, BACS 30-day default)
    Response r1 =
        post(
            "/suppliers",
            "{\"name\":\"ACME Supplies Ltd\",\"vatRegistered\":true,"
                + "\"vatNumber\":\"GB123456789\",\"countryCode\":\"GB\","
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String body = r1.readEntity(String.class);
    assertThat(body, containsString("ACME Supplies Ltd"));
    assertThat(body, containsString("GB"));
    assertThat(body, containsString("30")); // BACS default

    // Duplicate supplier name → 409
    Response rDup =
        post("/suppliers", "{\"name\":\"ACME Supplies Ltd\",\"vatRegistered\":false}", T);
    assertThat(rDup.getStatus(), is(409));

    // Tenant isolation — T2 cannot see T's suppliers
    Response rList = get("/suppliers", T2);
    assertThat(rList.getStatus(), is(200));
    assertThat(rList.readEntity(String.class), containsString("[]"));
  }

  // ── Gap #20 Test 2: Purchase Order lifecycle ──────────────────────────────────

  @Test
  void purchaseOrderLifecycle() {
    // Create supplier
    Response supRes =
        post("/suppliers", "{\"name\":\"Fresh Foods Ltd\",\"vatRegistered\":true}", T);
    assertThat(supRes.getStatus(), is(201));
    String supId = extractId(supRes.readEntity(String.class));

    // Create PO (DRAFT)
    Response poRes =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\","
                + "\"storeId\":\""
                + STORE_A
                + "\","
                + "\"currency\":\"GBP\","
                + "\"expectedDelivery\":\"2026-12-31\"}",
            T);
    assertThat(poRes.getStatus(), is(201));
    String poId = extractId(poRes.readEntity(String.class));

    // Add line
    Response lineRes =
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\""
                + VARIANT
                + "\","
                + "\"qty\":100,\"unitPrice\":9.99,\"vatCode\":\"T1\"}",
            T);
    assertThat(lineRes.getStatus(), is(201));

    // List lines
    Response linesRes = get("/purchase-orders/" + poId + "/lines", T);
    assertThat(linesRes.getStatus(), is(200));
    assertThat(linesRes.readEntity(String.class), containsString(VARIANT));

    // Submit PO
    Response submitRes = post("/purchase-orders/" + poId + "/submit", "{}", T);
    assertThat(submitRes.getStatus(), is(200));
    assertThat(submitRes.readEntity(String.class), containsString("SUBMITTED"));

    // Receive GRN
    Response grnRes =
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\","
                + "\"storeId\":\""
                + STORE_A
                + "\","
                + "\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qtyReceived\":100}]}",
            T);
    assertThat(grnRes.getStatus(), is(201));

    // PO should now be RECEIVED
    Response poGet = get("/purchase-orders/" + poId, T);
    assertThat(poGet.getStatus(), is(200));
    assertThat(poGet.readEntity(String.class), containsString("RECEIVED"));
  }

  // ── Gap #20 Test 3: Intercompany invoicing + FRS 102 nominal ledger ───────────

  @Test
  void intercompanyInvoicingAndNominalLedger() {
    // Raise intercompany invoice pair (AR + AP) with UK VAT T1 20%
    // net = £1000, VAT = £200, gross = £1200
    Response r1 =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_B
                + "\","
                + "\"netAmount\":1000.00,"
                + "\"vatAmount\":200.00,"
                + "\"grossAmount\":1200.00,"
                + "\"vatCode\":\"T1\","
                + "\"vatDisregarded\":false,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String pairBody = r1.readEntity(String.class);
    assertThat(pairBody, containsString("arInvoice"));
    assertThat(pairBody, containsString("apInvoice"));
    assertThat(pairBody, containsString("RAISED"));
    // BACS 30-day payment due date
    assertThat(pairBody, containsString("paymentDueDate"));

    String arId = extractNestedId(pairBody, "arInvoice");
    String apId = extractNestedId(pairBody, "apInvoice");

    // List invoices
    Response rList = get("/intercompany-invoices", T);
    assertThat(rList.getStatus(), is(200));
    assertThat(rList.readEntity(String.class), containsString("AR"));

    // Get specific invoice
    Response rGet = get("/intercompany-invoices/" + arId, T);
    assertThat(rGet.getStatus(), is(200));
    assertThat(rGet.readEntity(String.class), containsString("1000.00"));

    // Nominal ledger should have AR entries: 1100 Debtors DR, 2200 VAT Output CR, 4000 IC Sales CR
    Response ledger = get("/nominal-ledger", T);
    assertThat(ledger.getStatus(), is(200));
    String ledgerBody = ledger.readEntity(String.class);
    assertThat(ledgerBody, containsString("1100"));
    assertThat(ledgerBody, containsString("2200"));
    assertThat(ledgerBody, containsString("2100"));

    // Settle AR invoice → DR 1200 Bank / CR 1100 Debtors
    Response settleRes = post("/intercompany-invoices/" + arId + "/settle", "{}", T);
    assertThat(settleRes.getStatus(), is(200));

    // Settle again → 409 (already settled)
    Response settle2 = post("/intercompany-invoices/" + arId + "/settle", "{}", T);
    assertThat(settle2.getStatus(), is(409));

    // Tenant isolation — T2 cannot see T's invoices
    Response rIso = get("/intercompany-invoices/" + arId, T2);
    assertThat(rIso.getStatus(), is(404));
  }

  // ── Gap #20 Test 4: Group VAT disregard ──────────────────────────────────────

  @Test
  void intercompanyInvoiceGroupVatDisregarded() {
    // With vatDisregarded=true, no VAT nominal entries should be posted
    Response r1 =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_B
                + "\","
                + "\"netAmount\":500.00,"
                + "\"vatAmount\":0.00,"
                + "\"grossAmount\":500.00,"
                + "\"vatCode\":\"T1\","
                + "\"vatDisregarded\":true,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    assertThat(r1.readEntity(String.class), containsString("\"vatDisregarded\":true"));

    // Ledger should NOT contain 2200 VAT Output or 2201 VAT Input
    Response ledger = get("/nominal-ledger?code=2200", T);
    assertThat(ledger.getStatus(), is(200));
    assertThat(ledger.readEntity(String.class), containsString("[]"));

    // Negative: same fromStore and toStore → 400
    Response rBad =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_A
                + "\","
                + "\"netAmount\":100.00,"
                + "\"vatAmount\":20.00,"
                + "\"grossAmount\":120.00,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(rBad.getStatus(), is(400));
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static String extractNestedId(String json, String field) {
    int fieldPos = json.indexOf("\"" + field + "\"");
    int start = json.indexOf("\"id\":\"", fieldPos) + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }
}
