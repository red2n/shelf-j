package com.shelfj.purchase;

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
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
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
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * Reads carry a staff role because every read here is staff work — suppliers, purchase orders,
   * goods receipts and the nominal ledger are all back-office data. Before SJ-D10 this helper sent
   * no role and the requests still succeeded, which is precisely what was wrong.
   */
  private Response get(String pathAndQuery, String tenant) {
    return getAs(pathAndQuery, tenant, "OWNER");
  }

  private Response getAs(String pathAndQuery, String tenant, String roles) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    var req = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) req = req.header("X-Roles", roles);
    return req.get();
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

  // ── Partial receipt (horizon 2 item 5) ───────────────────────────────────────

  /** A submitted PO with one line for {@code qty}, ready to receive against. */
  private String submittedPo(String supplierName, int qty) {
    Response sup =
        post("/suppliers", "{\"name\":\"" + supplierName + "\",\"currency\":\"GBP\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\","
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                "{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":"
                    + qty
                    + ",\"unitPrice\":10.00,\"vatCode\":\"T1\"}",
                T)
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}", T).getStatus(), is(200));
    return poId;
  }

  private Response receive(String poId, String qty) {
    return post(
        "/goods-receipts",
        "{\"poId\":\""
            + poId
            + "\",\"storeId\":\""
            + STORE_A
            + "\","
            + "\"lines\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qtyReceived\":"
            + qty
            + "}]}",
        T);
  }

  private String status(String poId) {
    return get("/purchase-orders/" + poId, T).readEntity(String.class);
  }

  /**
   * The defect, and the half of it that was worse. A receipt used to set the order RECEIVED with no
   * reference to quantity — so 6 of 10 closed it, and the second delivery of the remaining 4 was
   * then refused because the order was no longer SUBMITTED. A split delivery stranded its own
   * balance with no purchase order left to receive it against.
   */
  @Test
  void aSplitDeliveryIsReceivedInPartsAndClosesOnlyWhenComplete() {
    String poId = submittedPo("Split Delivery Ltd", 10);

    assertThat(receive(poId, "6").getStatus(), is(201));
    assertThat(status(poId), containsString("PARTIALLY_RECEIVED"));

    // The balance. This is the call that used to fail.
    assertThat(receive(poId, "4").getStatus(), is(201));
    String body = status(poId);
    assertThat(body, containsString("\"status\":\"RECEIVED\""));
    assertThat(body, not(containsString("PARTIALLY_RECEIVED")));
  }

  /** The status alone does not say what is missing; the progress view does. */
  @Test
  void progressReportsWhatIsStillOutstanding() {
    String poId = submittedPo("Progress Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));

    String body = get("/purchase-orders/" + poId + "/progress", T).readEntity(String.class);
    assertThat(body, containsString("\"qtyOrdered\":10.000"));
    assertThat(body, containsString("\"qtyReceived\":6.000"));
    assertThat(body, containsString("\"qtyOutstanding\":4.000"));
  }

  /**
   * Accepting more than was ordered would book stock nobody asked for against an order that cannot
   * account for it, and a mistyped 60 for 6 would do it silently.
   */
  @Test
  void overReceiptIsRefused() {
    String poId = submittedPo("Over Delivery Ltd", 10);
    Response r = receive(poId, "11");
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_OVER_RECEIPT"));
    // And it is refused as a whole: the order is untouched, not left half-updated.
    assertThat(status(poId), containsString("SUBMITTED"));
  }

  /** Over-receipt across two deliveries is the same fault, and the second one is where it shows. */
  @Test
  void overReceiptIsRefusedCumulativelyNotOnlyPerDelivery() {
    String poId = submittedPo("Cumulative Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));
    Response second = receive(poId, "6"); // 12 against an order of 10
    assertThat(second.getStatus(), is(422));
    assertThat(second.readEntity(String.class), containsString("PURCHASE_OVER_RECEIPT"));
    // The first delivery still stands.
    assertThat(status(poId), containsString("PARTIALLY_RECEIVED"));
  }

  /**
   * Without a short close, a partially received order the supplier never completes sits in
   * PARTIALLY_RECEIVED for good — the same dead end SJ-D3 fixed for DRAFT and SUBMITTED.
   */
  @Test
  void aPartiallyReceivedOrderCanBeShortClosed() {
    String poId = submittedPo("Short Close Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));

    Response closed =
        post("/purchase-orders/" + poId + "/close", "{\"reason\":\"supplier discontinued\"}", T);
    assertThat(closed.getStatus(), is(200));
    String body = closed.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"CLOSED\""));
    assertThat(body, containsString("supplier discontinued"));

    // A closed order is no longer receivable — the balance was abandoned deliberately.
    assertThat(receive(poId, "4").getStatus(), is(400));
  }

  /** CLOSED is for a partly delivered order. The other two states have their own answers. */
  @Test
  void onlyAPartiallyReceivedOrderCanBeShortClosed() {
    String submitted = submittedPo("Nothing Yet Ltd", 10);
    Response r = post("/purchase-orders/" + submitted + "/close", "{\"reason\":\"n/a\"}", T);
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_PO_NOT_CLOSEABLE"));

    String full = submittedPo("All Arrived Ltd", 10);
    assertThat(receive(full, "10").getStatus(), is(201));
    assertThat(
        post("/purchase-orders/" + full + "/close", "{\"reason\":\"n/a\"}", T).getStatus(),
        is(409));
  }

  /** A replayed receipt must not count its quantity twice — the SJ-D15 question, asked here. */
  @Test
  void aReplayedReceiptDoesNotCountTwice() {
    String poId = submittedPo("Replay Ltd", 10);
    String key = "grn-replay-" + java.util.UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      Response r =
          target
              .path("/goods-receipts")
              .request()
              .header("X-Tenant-Id", T)
              .header("X-Roles", "OWNER")
              .header("Idempotency-Key", key)
              .post(
                  Entity.entity(
                      "{\"poId\":\""
                          + poId
                          + "\",\"storeId\":\""
                          + STORE_A
                          + "\","
                          + "\"lines\":[{\"variantId\":\""
                          + VARIANT
                          + "\",\"qtyReceived\":6}]}",
                      MediaType.APPLICATION_JSON));
      assertThat(r.getStatus(), is(201));
    }
    String body = get("/purchase-orders/" + poId + "/progress", T).readEntity(String.class);
    assertThat(body, containsString("\"qtyReceived\":6.000"));
    assertThat(body, containsString("\"qtyOutstanding\":4.000"));
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

  // ── SJ-D3: CANCELLED was an unreachable state ────────────────────────────────

  /** A DRAFT purchase order raised in error can be cancelled, with its reason recorded. */
  @Test
  void draftPurchaseOrderCanBeCancelled() {
    String poId = draftPurchaseOrder("Cancel Me Ltd");

    Response cancelled =
        post(
            "/purchase-orders/" + poId + "/cancel",
            "{\"reason\":\"raised against wrong store\"}",
            T);
    assertThat(cancelled.getStatus(), is(200));
    String body = cancelled.readEntity(String.class);
    assertThat(body, containsString("CANCELLED"));
    assertThat(body, containsString("raised against wrong store"));

    // The cancellation survives a re-read, and the reason is on the order itself.
    String reread = get("/purchase-orders/" + poId, T).readEntity(String.class);
    assertThat(reread, containsString("CANCELLED"));
    assertThat(reread, containsString("raised against wrong store"));
  }

  /** A SUBMITTED order is still cancellable, and cancelling it closes it to further receipts. */
  @Test
  void submittedPurchaseOrderCanBeCancelledAndIsThenUnreceivable() {
    String poId = draftPurchaseOrder("Submitted Then Cancelled Ltd");
    assertThat(post("/purchase-orders/" + poId + "/lines", line(), T).getStatus(), is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}", T).getStatus(), is(200));

    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"supplier out of stock\"}", T)
            .getStatus(),
        is(200));

    // Receiving against a cancelled order must fail -- otherwise stock would be booked against a
    // commitment that no longer exists.
    Response received =
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qtyReceived\":5}]}",
            T);
    assertThat(received.getStatus(), is(400));
  }

  /**
   * A received order holds stock booked against it, so cancelling would orphan that stock; and a
   * second cancel is refused rather than silently discarding the new caller's reason.
   */
  @Test
  void receivedOrCancelledPurchaseOrdersCannotBeCancelled() {
    // RECEIVED → 409
    String receivedPo = draftPurchaseOrder("Already Received Ltd");
    assertThat(post("/purchase-orders/" + receivedPo + "/lines", line(), T).getStatus(), is(201));
    assertThat(post("/purchase-orders/" + receivedPo + "/submit", "{}", T).getStatus(), is(200));
    assertThat(
        post(
                "/goods-receipts",
                "{\"poId\":\""
                    + receivedPo
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qtyReceived\":10}]}",
                T)
            .getStatus(),
        is(201));
    Response afterReceipt =
        post("/purchase-orders/" + receivedPo + "/cancel", "{\"reason\":\"too late\"}", T);
    assertThat(afterReceipt.getStatus(), is(409));
    assertThat(
        afterReceipt.readEntity(String.class), containsString("PURCHASE_PO_NOT_CANCELLABLE"));

    // Already CANCELLED → 409, so the first reason recorded is the one that stands.
    String cancelledPo = draftPurchaseOrder("Double Cancel Ltd");
    assertThat(
        post("/purchase-orders/" + cancelledPo + "/cancel", "{\"reason\":\"first\"}", T)
            .getStatus(),
        is(200));
    Response second =
        post("/purchase-orders/" + cancelledPo + "/cancel", "{\"reason\":\"second\"}", T);
    assertThat(second.getStatus(), is(409));
    assertThat(
        get("/purchase-orders/" + cancelledPo, T).readEntity(String.class),
        containsString("first"));
  }

  /** A cancellation with no stated reason is unauditable, so it is rejected. */
  @Test
  void cancellationRequiresAReason() {
    String poId = draftPurchaseOrder("No Reason Ltd");
    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"  \"}", T).getStatus(),
        is(400));
    assertThat(post("/purchase-orders/" + poId + "/cancel", "{}", T).getStatus(), is(400));
    // Still cancellable afterwards -- a rejected request must not have moved the state.
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class), containsString("DRAFT"));
  }

  /** Cancelling is tenant-scoped: another tenant cannot reach this order at all. */
  @Test
  void cancellationIsTenantScoped() {
    String poId = draftPurchaseOrder("Isolated Ltd");
    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"not yours\"}", T2).getStatus(),
        is(404));
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class), containsString("DRAFT"));
  }

  // ── SJ-D9: a mistyped date is the caller's mistake, not a server fault ───────

  /**
   * A malformed date must come back 400 naming the field it came from, never 500. These three
   * inputs reached {@code LocalDate.parse} raw, so a caller's typo threw out of business code and
   * fell through to GenericExceptionMapper as INTERNAL_ERROR — wrong per golden rule #15, and it
   * makes a client error look like an outage to alerting.
   */
  @Test
  void malformedDatesAreRejectedAsBadRequestNamingTheField() {
    Response sup = post("/suppliers", "{\"name\":\"Typo Traders Ltd\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));

    // A full instant where the field takes yyyy-MM-dd: the plausible wrong guess, and the shape
    // that produced the 500 in pricing-svc when its own @Schema said only "ISO-8601 date".
    Response bad =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"expectedDelivery\":\"2026-12-31T00:00:00Z\"}",
            T);
    assertThat(bad.getStatus(), is(400));
    String body = bad.readEntity(String.class);
    assertThat(body, containsString("INVALID_DATE"));
    assertThat(body, containsString("expectedDelivery must be yyyy-MM-dd"));

    // The nominal-ledger range is the same class of input and names itself the same way.
    Response badRange = get("/nominal-ledger?from=last-tuesday", T);
    assertThat(badRange.getStatus(), is(400));
    assertThat(badRange.readEntity(String.class), containsString("from must be yyyy-MM-dd"));

    // ...and the documented form still works, so the guard did not simply reject everything.
    Response ok =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"expectedDelivery\":\"2026-12-31\"}",
            T);
    assertThat(ok.getStatus(), is(201));
  }

  // ── SJ-D10: procurement is back-office data, not storefront data ────────────

  /**
   * Every read in this service used to answer any caller holding a token for the tenant, because
   * none of these paths sits under /admin/ and nothing in purchase-svc called requireAnyRole. A
   * signed-in storefront shopper could list the tenant's suppliers and their payment terms, its
   * purchase orders, and its nominal ledger.
   */
  @Test
  void procurementReadsRequireAStaffRole() {
    for (String path :
        new String[] {
          "/suppliers",
          "/purchase-orders",
          "/goods-receipts?purchaseOrderId=" + STORE_A,
          "/intercompany-invoices",
          "/nominal-ledger"
        }) {
      assertThat("no role: " + path, getAs(path, T, null).getStatus(), is(403));
      assertThat("customer: " + path, getAs(path, T, "CUSTOMER").getStatus(), is(403));
    }
    // Staff still work, or the gate would just be an outage.
    assertThat(getAs("/suppliers", T, "STOREKEEPER").getStatus(), is(200));
    assertThat(getAs("/purchase-orders", T, "OWNER").getStatus(), is(200));
  }

  /** Creates a supplier and a DRAFT purchase order against it, returning the PO id. */
  private String draftPurchaseOrder(String supplierName) {
    Response sup = post("/suppliers", "{\"name\":\"" + supplierName + "\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"currency\":\"GBP\"}",
            T);
    assertThat(po.getStatus(), is(201));
    return extractId(po.readEntity(String.class));
  }

  private static String line() {
    return "{\"variantId\":\"" + VARIANT + "\",\"qty\":10,\"unitPrice\":2.50}";
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
