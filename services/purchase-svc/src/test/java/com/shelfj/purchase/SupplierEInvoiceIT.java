package com.shelfj.purchase;

import static com.shelfj.purchase.PaymentRunSteps.assertCode;
import static com.shelfj.purchase.PaymentRunSteps.data;
import static com.shelfj.purchase.PurchaseFixtures.STORE_A;
import static com.shelfj.purchase.PurchaseFixtures.T;
import static com.shelfj.purchase.PurchaseFixtures.T2;
import static com.shelfj.purchase.PurchaseFixtures.USER;
import static com.shelfj.purchase.PurchaseFixtures.VARIANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.shelfj.einvoice.EInvoices;
import com.shelfj.einvoice.Invoice;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.TenantSvcStub;
import com.shelfj.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Supplier e-invoices received (07.13): a UBL invoice or a Factur-X PDF read, checked, matched and
 * captured through the three-way match; what cannot be matched waits for a person, who teaches it;
 * what breaks the rules, or is addressed elsewhere, is kept and not captured; and what an attacker
 * or another business sends is refused.
 */
@HelidonTest
class SupplierEInvoiceIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  /** This business, as its e-invoices name it. */
  private static final String OUR_VAT = "GB123456789";

  static {
    System.setProperty("shelfj.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .withIdentity(T, OUR_VAT, null, null)
        .with(T2, "GBP", "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── the flow ─────────────────────────────────────────────────────────────────

  @Test
  void anInvoiceThatNamesItsOrderAndLinesIsCapturedAndKeptAsItArrived() {
    String supplier = supplier("Acme Wholesale", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    byte[] doc =
        ubl(
            invoice(
                "INV-100",
                "GB999999973",
                null,
                OUR_VAT,
                po,
                "380",
                null,
                line("Apples", "A-1", "1", "10", "2.50")));

    JsonObject received = data(send(doc, "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CAPTURED"));
    assertThat(received.getString("syntax"), is("UBL"));
    String invoiceId = received.getString("supplierInvoiceId");
    JsonObject invoice = data(get("/supplier-invoices/" + invoiceId, T, "OWNER"), 200);
    assertThat(invoice.toString(), invoice.getString("status"), is("MATCHED"));
    assertThat(invoice.getString("invoiceNumber"), is("INV-100"));

    String id = received.getString("id");
    Response original = as("/e-invoices/" + id + "/document", T, "OWNER").get();
    assertThat(original.getStatus(), is(200));
    assertArrayEquals(doc, original.readEntity(byte[].class));

    JsonObject again = data(send(doc, "application/xml", T, "OWNER"), 200);
    assertThat(again.getString("id"), is(id));
    assertThat(again.getBoolean("alreadyReceived"), is(true));
    assertThat(count("supplier_invoices"), is(1));
    assertThat(text(get("/e-invoices?status=CAPTURED", T, "OWNER"), 200), containsString(id));
  }

  @Test
  void aFacturXPdfIsReadFromTheCiiInsideIt() {
    String supplier = supplier("Dupont SA", "GB999999973", null, null);
    String po = receivedOrder(supplier, 4, "12.00");
    byte[] pdf =
        EInvoices.toFacturX(
            invoice(
                "FX-7",
                "GB999999973",
                null,
                OUR_VAT,
                po,
                "380",
                null,
                line("Cheese", "C-1", "1", "4", "12.00")));
    JsonObject received = data(send(pdf, "application/pdf", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CAPTURED"));
    assertThat(received.getString("container"), is("PDF"));
    assertThat(received.getString("syntax"), is("CII"));
    assertThat(received.getString("embeddedFilename"), is("factur-x.xml"));
  }

  @Test
  void aNonCompliantInvoiceIsKeptNotCapturedAndCanOnlyBeRefused() {
    String supplier = supplier("Sloppy Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String xml =
        new String(
                ubl(
                    invoice(
                        "BAD-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po,
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                StandardCharsets.UTF_8)
            .replace(
                "<cbc:PayableAmount currencyID=\"GBP\">30.00</cbc:PayableAmount>",
                "<cbc:PayableAmount currencyID=\"GBP\">3.00</cbc:PayableAmount>");
    JsonObject received =
        data(send(xml.getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("NOT_COMPLIANT"));
    assertThat(received.toString(), containsString("BR-CO-16"));
    assertThat(count("supplier_invoices"), is(0));
    String id = received.getString("id");

    assertCode(
        post("/e-invoices/" + id + "/match", "{}", T, "OWNER"),
        409,
        "PURCHASE_EINVOICE_NOT_COMPLIANT");
    assertCode(
        post(
            "/e-invoices/" + id + "/refuse",
            "{\"reason\":\"Totals do not add up\"}",
            T,
            "STOREKEEPER"),
        403,
        "");
    assertCode(post("/e-invoices/" + id + "/refuse", "{\"reason\":\" \"}", T, "OWNER"), 400, "");
    JsonObject refused =
        data(
            post(
                "/e-invoices/" + id + "/refuse",
                "{\"reason\":\"Totals do not add up\"}",
                T,
                "OWNER"),
            200);
    assertThat(refused.getString("status"), is("REFUSED"));
    assertThat(refused.getString("decisionReason"), is("Totals do not add up"));
    assertCode(
        post("/e-invoices/" + id + "/refuse", "{\"reason\":\"again\"}", T, "OWNER"),
        409,
        "PURCHASE_EINVOICE_SETTLED");
  }

  @Test
  void anUnknownSupplierWaitsAndItsAddressIsRememberedOnceMatched() {
    String supplier = supplier("Northern Farms", null, null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    byte[] first =
        ubl(
            invoice(
                "NF-1",
                "GB555555555",
                "GB555555555",
                OUR_VAT,
                po,
                "380",
                null,
                line("Apples", null, "1", "10", "2.50")));
    JsonObject waiting = data(send(first, "application/xml", T, "OWNER"), 201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_SUPPLIER"));
    assertThat(waiting.getString("problem"), containsString("9932:GB555555555"));

    JsonObject matched =
        data(
            post(
                "/e-invoices/" + waiting.getString("id") + "/match",
                "{\"supplierId\":\"" + supplier + "\",\"remember\":true}",
                T,
                "OWNER"),
            200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));
    JsonObject remembered = data(get("/suppliers/" + supplier, T, "OWNER"), 200);
    assertThat(remembered.getString("einvoiceScheme"), is("9932"));
    assertThat(remembered.getString("einvoiceId"), is("GB555555555"));

    String po2 = receivedOrder(supplier, 10, "2.50");
    byte[] second =
        ubl(
            invoice(
                "NF-2",
                "GB555555555",
                "GB555555555",
                OUR_VAT,
                po2,
                "380",
                null,
                line("Apples", null, "1", "10", "2.50")));
    JsonObject next = data(send(second, "application/xml", T, "OWNER"), 201);
    assertThat("found by the address it was taught", next.getString("status"), is("CAPTURED"));
  }

  @Test
  void linesWithoutReferencesWaitAndTheCodeATaughtLineCarriesMatchesTheNextInvoice()
      throws Exception {
    String supplier = supplier("Code Supplies", "GB999999973", null, null);
    String po = receivedOrder(supplier, 6, "5.00");
    JsonObject waiting =
        data(
            send(
                ubl(
                    invoice(
                        "CS-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po,
                        "380",
                        null,
                        line("Widgets", "SKU-9", null, "6", "5.00"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_LINES"));
    assertThat(waiting.getString("problem"), containsString("Widgets"));

    String poLine = orderLines(po).get(0);
    assertCode(
        post(
            "/e-invoices/" + waiting.getString("id") + "/match",
            "{\"lines\":[{\"position\":2,\"poLineId\":\"" + poLine + "\"}]}",
            T,
            "OWNER"),
        400,
        "PURCHASE_EINVOICE_LINE_UNKNOWN");
    assertCode(
        post(
            "/e-invoices/" + waiting.getString("id") + "/match",
            "{\"lines\":[{\"position\":1,\"poLineId\":\"" + UUID.randomUUID() + "\"}]}",
            T,
            "OWNER"),
        400,
        "PURCHASE_EINVOICE_LINE_NOT_ON_ORDER");
    JsonObject matched =
        data(
            post(
                "/e-invoices/" + waiting.getString("id") + "/match",
                "{\"remember\":true,\"lines\":[{\"position\":1,\"poLineId\":\"" + poLine + "\"}]}",
                T,
                "OWNER"),
            200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));

    String po2 = receivedOrder(supplier, 6, "5.00");
    JsonObject next =
        data(
            send(
                ubl(
                    invoice(
                        "CS-2",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po2,
                        "380",
                        null,
                        line("Widgets", "SKU-9", null, "6", "5.00"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(next.toString(), next.getString("status"), is("CAPTURED"));
    assertThat(next.toString(), containsString("\"matchedBy\":\"ITEM_CODE\""));
  }

  @Test
  void anInvoiceThatNamesNoOrderWaitsForOneAndAnotherSuppliersOrderIsRefused() {
    String supplier = supplier("No Ref Ltd", "GB999999973", null, null);
    String other = supplier("Someone Else", null, null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String theirs = receivedOrder(other, 10, "2.50");
    JsonObject waiting =
        data(
            send(
                ubl(
                    invoice(
                        "NR-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        "4500012345",
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_ORDER"));
    String id = waiting.getString("id");
    assertCode(
        post("/e-invoices/" + id + "/match", "{\"poId\":\"" + theirs + "\"}", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_ORDER_NOT_SUPPLIERS");
    JsonObject matched =
        data(post("/e-invoices/" + id + "/match", "{\"poId\":\"" + po + "\"}", T, "OWNER"), 200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));
  }

  @Test
  void anInvoiceAddressedToAnotherBusinessIsKeptAndNotCaptured() {
    String supplier = supplier("Wrong Door", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    JsonObject received =
        data(
            send(
                ubl(
                    invoice(
                        "WD-1",
                        "GB999999973",
                        null,
                        "GB987654321",
                        po,
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(received.toString(), received.getString("status"), is("MISDIRECTED"));
    assertThat(received.getString("problem"), containsString("GB987654321"));
    assertCode(
        post(
            "/e-invoices/" + received.getString("id") + "/match",
            "{\"poId\":\"" + po + "\"}",
            T,
            "OWNER"),
        409,
        "PURCHASE_EINVOICE_MISDIRECTED");
    assertThat(count("supplier_invoices"), is(0));
  }

  @Test
  void theSameInvoiceNumberSentAgainInADifferentFileIsADuplicate() {
    String supplier = supplier("Twice Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 20, "2.50");
    sendApples("TW-1", po, "Apples");
    JsonObject second = sendApples("TW-1", po, "Apples, resent");
    assertThat(second.toString(), second.getString("status"), is("DUPLICATE"));
    assertThat(count("supplier_invoices"), is(1));
  }

  @Test
  void aCreditNoteClosesTheReturnItCredits() {
    String supplier = supplier("Returns Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    sendApples("RT-1", po, "Apples");
    JsonObject ret =
        data(
            post(
                "/vendor-returns",
                "{\"poId\":\""
                    + po
                    + "\",\"reason\":\"DAMAGED\",\"notes\":\"crushed\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":2}]}",
                T,
                "OWNER"),
            201);
    assertThat(ret.getString("status"), is("RAISED"));

    byte[] credit =
        ubl(
            invoice(
                "CN-1",
                "GB999999973",
                null,
                OUR_VAT,
                null,
                "381",
                "RT-1",
                line("Apples returned", null, null, "2", "2.50")));
    JsonObject received = data(send(credit, "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CREDITED"));
    assertThat(received.getBoolean("creditNote"), is(true));
    assertThat(received.getString("vendorReturnId"), is(ret.getString("id")));
    assertThat(
        data(get("/vendor-returns/" + ret.getString("id"), T, "OWNER"), 200).getString("status"),
        is("CREDITED"));
  }

  // ── refusals ─────────────────────────────────────────────────────────────────

  @Test
  void hostileDocumentsAndAnotherBusinessesReadsAreRefused() {
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">&x;</Invoice>";
    assertCode(
        send(xxe.getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_DTD_REFUSED");
    assertCode(
        send("<Order/>".getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_NOT_AN_INVOICE");
    assertCode(
        send("%PDF-1.7 not really".getBytes(StandardCharsets.UTF_8), "application/pdf", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_PDF_UNREADABLE");
    assertCode(send(new byte[0], "application/xml", T, "OWNER"), 400, "PURCHASE_EINVOICE_EMPTY");
    Response json =
        as("/e-invoices", T, "OWNER").post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(json.readEntity(String.class), json.getStatus(), is(415));
    assertThat(count("supplier_einvoices"), is(0));

    String supplier = supplier("Private Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String id = sendApples("PV-1", po, "Apples").getString("id");
    assertCode(get("/e-invoices/" + id, T2, "OWNER"), 404, "PURCHASE_EINVOICE_NOT_FOUND");
    assertThat(as("/e-invoices/" + id + "/document", T2, "OWNER").get().getStatus(), is(404));
    assertCode(
        post("/e-invoices/" + id + "/refuse", "{\"reason\":\"not mine\"}", T2, "OWNER"),
        404,
        "PURCHASE_EINVOICE_NOT_FOUND");
    assertThat(text(get("/e-invoices", T2, "OWNER"), 200), containsString("\"data\":[]"));
    assertCode(get("/e-invoices?status=LOST", T, "OWNER"), 400, "PURCHASE_EINVOICE_STATUS_INVALID");
  }

  @Test
  void tenPeopleMatchingOneInvoiceAtOnceMakeOneInvoice() throws Exception {
    String supplier = supplier("Busy Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 6, "5.00");
    String id =
        data(
                send(
                    ubl(
                        invoice(
                            "BZ-1",
                            "GB999999973",
                            null,
                            OUR_VAT,
                            po,
                            "380",
                            null,
                            line("Widgets", "SKU-1", null, "6", "5.00"))),
                    "application/xml",
                    T,
                    "OWNER"),
                201)
            .getString("id");
    String poLine = orderLines(po).get(0);
    String choice = "{\"lines\":[{\"position\":1,\"poLineId\":\"" + poLine + "\"}]}";
    List<Integer> statuses =
        PaymentRunSteps.inParallel(
            10,
            () -> {
              Response r = post("/e-invoices/" + id + "/match", choice, T, "OWNER");
              r.readEntity(String.class);
              return r.getStatus();
            });
    for (int s : statuses) assertThat(statuses.toString(), s, anyOf(is(200), is(409)));
    assertThat(count("supplier_invoices"), is(1));
    assertThat(data(get("/e-invoices/" + id, T, "OWNER"), 200).getString("status"), is("CAPTURED"));
  }

  // ── steps ─────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String pathAndQuery, String tenant, String role) {
    return WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role);
  }

  private Response post(String path, String json, String tenant, String role) {
    return as(path, tenant, role).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String text(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private Response get(String pathAndQuery, String tenant, String role) {
    return as(pathAndQuery, tenant, role).get();
  }

  private Response send(byte[] document, String type, String tenant, String role) {
    return as("/e-invoices", tenant, role).post(Entity.entity(document, type));
  }

  private String supplier(String name, String vat, String scheme, String endpoint) {
    StringBuilder json =
        new StringBuilder("{\"name\":\"")
            .append(name)
            .append("\",\"currency\":\"GBP\",\"paymentTermsDays\":30");
    if (vat != null)
      json.append(",\"vatNumber\":\"").append(vat).append("\",\"vatRegistered\":true");
    if (scheme != null)
      json.append(",\"einvoiceScheme\":\"")
          .append(scheme)
          .append("\",\"einvoiceId\":\"")
          .append(endpoint)
          .append('"');
    return data(post("/suppliers", json.append('}').toString(), T, "OWNER"), 201).getString("id");
  }

  /** A submitted order for {@code qty} of the variant at the price, received in full. */
  private String receivedOrder(String supplierId, int qty, String price) {
    String po =
        data(post("/purchase-orders", PurchaseFixtures.orderJson(supplierId), T, "OWNER"), 201)
            .getString("id");
    data(
        post(
            "/purchase-orders/" + po + "/lines", PurchaseFixtures.lineJson(qty, price), T, "OWNER"),
        201);
    data(post("/purchase-orders/" + po + "/submit", "{}", T, "OWNER"), 200);
    data(post("/goods-receipts", PurchaseFixtures.receiptJson(po, qty), T, "OWNER"), 201);
    return po;
  }

  private List<String> orderLines(String po) throws Exception {
    List<String> ids = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT id FROM purchase.purchase_order_lines WHERE tenant_id = ?::uuid AND po_id = ?::uuid"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, T);
      ps.setString(2, po);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) ids.add(rs.getString(1));
      }
    }
    return ids;
  }

  private int count(String table) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM purchase." + table)) {
      return rs.next() ? rs.getInt(1) : -1;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── documents a supplier sends ──────────────────────────────────────────────

  private record Item(String name, String sellersId, String orderLine, String qty, String price) {}

  private static Item line(
      String name, String sellersId, String orderLine, String qty, String price) {
    return new Item(name, sellersId, orderLine, qty, price);
  }

  private static byte[] ubl(Invoice invoice) {
    return EInvoices.toUbl(invoice).getBytes(StandardCharsets.UTF_8);
  }

  /** Ten apples at 2.50 against the order's first line, from GB999999973, sent by the owner. */
  private JsonObject sendApples(String number, String po, String name) {
    return data(
        send(
            ubl(
                invoice(
                    number,
                    "GB999999973",
                    null,
                    OUR_VAT,
                    po,
                    "380",
                    null,
                    line(name, null, "1", "10", "2.50"))),
            "application/xml",
            T,
            "OWNER"),
        201);
  }

  /** A Peppol BIS Billing 3.0 invoice at 20% VAT that breaks no rule, from a UK supplier to us. */
  private static Invoice invoice(
      String number,
      String sellerVat,
      String sellerEndpoint,
      String buyerVat,
      String orderRef,
      String typeCode,
      String preceding,
      Item... items) {
    List<Invoice.Line> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < items.length; i++) {
      Item it = items[i];
      BigDecimal qty = new BigDecimal(it.qty());
      BigDecimal price = new BigDecimal(it.price());
      BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
      net = net.add(amount);
      lines.add(
          new Invoice.Line(
              Integer.toString(i + 1),
              null,
              null,
              qty,
              "C62",
              amount,
              it.orderLine(),
              null,
              null,
              List.of(),
              new Invoice.Price(price, null, null, null, null),
              "S",
              new BigDecimal("20"),
              new Invoice.Item(
                  it.name(), null, it.sellersId(), null, null, List.of(), null, List.of())));
    }
    BigDecimal vat = net.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
    BigDecimal gross = net.add(vat);
    Invoice.Address london =
        new Invoice.Address("1 High Street", null, null, "London", "E1 6AN", null, "GB");
    Invoice.Identifier from =
        sellerEndpoint != null
            ? new Invoice.Identifier(sellerEndpoint, "9932")
            : new Invoice.Identifier("5790000435975", "0088");
    Invoice.Party seller =
        new Invoice.Party(
            "Supplier Ltd", null, List.of(), null, sellerVat, null, null, from, london, null);
    Invoice.Party buyer =
        new Invoice.Party(
            "Corner Shop Ltd",
            null,
            List.of(),
            null,
            buyerVat,
            null,
            null,
            new Invoice.Identifier(buyerVat, "9932"),
            london,
            null);
    return new Invoice(
        Invoice.PEPPOL_BIS_3,
        Invoice.PEPPOL_BILLING_PROFILE,
        number,
        LocalDate.of(2026, 9, 10),
        typeCode,
        "GBP",
        null,
        null,
        null,
        LocalDate.of(2026, 10, 10),
        "SHOP-1",
        null,
        null,
        orderRef,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        preceding == null
            ? List.of()
            : List.of(new Invoice.PrecedingInvoice(preceding, LocalDate.of(2026, 9, 10))),
        seller,
        buyer,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        new Invoice.Totals(net, null, null, net, vat, null, gross, null, null, gross),
        List.of(new Invoice.VatBreakdown(net, vat, "S", new BigDecimal("20"), null, null)),
        List.of(),
        lines);
  }

  @SuppressWarnings("unused")
  private static final String STORE = STORE_A;
}
