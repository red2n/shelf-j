package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gapless legal receipt sequence.
 *
 * <p>Fiscal law in several markets requires consecutive receipt numbers with no holes, and requires
 * that the absence of holes can be shown to an inspector. The interesting tests here are the ones
 * about what must <em>not</em> happen: a number burned by a sale that never completes, a second
 * number for one sale, and a voided receipt quietly closing its own gap.
 */
@HelidonTest
class FiscalReceiptIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "order");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.order.pricing.enforce", "false");
    System.setProperty("shelfj.order.inventory.reserve-enforce", "false");
  }

  private static final String T = "01a090ae-611e-702a-9bdf-bcc7032115c4";
  private static final String S = "01a090ae-611e-7035-a4da-400bf673cfe8";
  private static final String S2 = "01a090ae-611e-703a-b34c-b0ca607d8240";
  private static final String V = "01a090ae-611e-7055-9838-5de027ce9e0a";

  @Inject WebTarget target;
  @Inject com.shelfj.order.service.OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Placing an order needs an Idempotency-Key (golden rule 11); a fresh one per call. */
  private Response place(String json, String tenant) {
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) {
      t = t.queryParam(params[i], params[i + 1]);
    }
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private static String extractId(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  /** Places a POS order and confirms it — a completed sale. Returns the order id. */
  private String sell(String store, String total) {
    Response r =
        place(
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":"
                + total
                + "}],"
                + "\"currency\":\"GBP\"}",
            T);
    String placed = r.readEntity(String.class);
    assertThat(placed, r.getStatus(), is(201));
    String id = extractId(placed);
    assertThat(post("/orders/" + id + "/confirm", "{}", T).getStatus(), is(200));
    return id;
  }

  private String placeOnly(String store) {
    Response r =
        place(
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"GBP\"}",
            T);
    return extractId(r.readEntity(String.class));
  }

  private long numberOf(String orderId) {
    Response r = get("/admin/orders/" + orderId + "/fiscal-receipt", T);
    assertThat(r.getStatus(), is(200));
    var m = Pattern.compile("\"number\":(\\d+)").matcher(r.readEntity(String.class));
    assertThat(m.find(), is(true));
    return Long.parseLong(m.group(1));
  }

  private String audit(String store) {
    return get(
            "/admin/fiscal-receipts/audit",
            T,
            "storeId",
            store,
            "period",
            String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear()))
        .readEntity(String.class);
  }

  // ── the sequence ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A completed sale is numbered without anyone asking")
  void numberedOnConfirm() {
    String order = sell(S, "10.00");
    Response r = get("/admin/orders/" + order + "/fiscal-receipt", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    // A sequence that only numbers the sales somebody remembered to print is not a sequence.
    assertThat(body, containsString("\"number\":"));
    assertThat(body, containsString("\"fullNumber\":"));
  }

  @Test
  @DisplayName("Numbers run consecutively, one per sale")
  void consecutive() {
    String a = sell(S2, "1.00");
    String b = sell(S2, "2.00");
    String c = sell(S2, "3.00");
    long na = numberOf(a);
    assertThat(numberOf(b), is(na + 1));
    assertThat(numberOf(c), is(na + 2));
  }

  @Test
  @DisplayName("A basket that is never paid for burns no number")
  void pendingBurnsNothing() {
    String store = Ids.newId().toString();
    String abandoned = placeOnly(store);

    // Issuing for a PENDING order is refused — this is where gaps come from.
    Response refused = post("/admin/orders/" + abandoned + "/fiscal-receipt", "{}", T);
    assertThat(refused.getStatus(), is(400));
    assertThat(refused.readEntity(String.class), containsString("ORDER_NOT_SELLABLE"));

    // The next real sale still gets number 1, not 2.
    String real = sell(store, "9.99");
    assertThat(numberOf(real), is(1L));
  }

  @Test
  @DisplayName("Reprinting returns the number already issued, never a second one")
  void issuingTwiceIsIdempotent() {
    String order = sell(Ids.newId().toString(), "4.00");
    long first = numberOf(order);

    Response again = post("/admin/orders/" + order + "/fiscal-receipt", "{}", T);
    assertThat(again.getStatus(), is(200));
    var m = Pattern.compile("\"number\":(\\d+)").matcher(again.readEntity(String.class));
    assertThat(m.find(), is(true));
    // Two numbers for one sale is how a day's takings get counted twice.
    assertThat(Long.parseLong(m.group(1)), is(first));
  }

  @Test
  @DisplayName("Two stores keep separate sequences, and both start at one")
  void perStoreSequences() {
    String x = Ids.newId().toString();
    String y = Ids.newId().toString();
    assertThat(numberOf(sell(x, "1.00")), is(1L));
    assertThat(numberOf(sell(y, "1.00")), is(1L));
    assertThat(numberOf(sell(x, "2.00")), is(2L));
  }

  @Test
  @DisplayName("A voided sale keeps its number, and the sequence stays intact")
  void voidKeepsTheNumber() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String voided = sell(store, "2.00");
    sell(store, "3.00");
    long n = numberOf(voided);

    assertThat(
        post("/orders/" + voided + "/void", "{\"reason\":\"wrong item scanned\"}", T).getStatus(),
        is(200));

    Response r = get("/admin/orders/" + voided + "/fiscal-receipt", T);
    String body = r.readEntity(String.class);
    // Ring the sale, take the cash, void the receipt, close the gap, and the till balances. The
    // number staying is what stops that.
    assertThat(body, containsString("\"number\":" + n));
    assertThat(body, containsString("\"voidedAt\""));
    assertThat(body, containsString("wrong item scanned"));
    assertThat(audit(store), containsString("\"intact\":true"));
  }

  @Test
  @DisplayName("A till sale is numbered when the payment that completes it lands")
  void aTillSaleIsNumberedWhenPaymentCompletesIt() {
    // The first version of this sequence hooked only confirmOrder, which the till never calls:
    // till sales are confirmed by payment capture. So it numbered the sales a manager confirmed by
    // hand and almost none of the ones rung up at a till — the ones fiscal law is written about.
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    UUID tenant = UUID.fromString(T);
    UUID order = UUID.fromString(orderId);

    // A split tender: nothing is numbered until the sale is complete.
    orderService.handlePaymentCaptured(tenant, order, Ids.newId(), new BigDecimal("2.00"));
    assertThat(get("/admin/orders/" + orderId + "/fiscal-receipt", T).getStatus(), is(404));

    orderService.handlePaymentCaptured(tenant, order, Ids.newId(), new BigDecimal("3.00"));
    assertThat(numberOf(orderId), is(1L));
  }

  private Response getAs(String path, String tenant, String roles) {
    return target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
  }

  @Test
  @DisplayName("The till can read the number it has to print; a stranger cannot")
  void theTillCanReadItsReceiptNumber() {
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    orderService.handlePaymentCaptured(
        UUID.fromString(T), UUID.fromString(orderId), Ids.newId(), new BigDecimal("5.00"));

    Response asCashier = getAs("/orders/" + orderId + "/fiscal-receipt", T, "CASHIER");
    assertThat(asCashier.getStatus(), is(200));
    assertThat(asCashier.readEntity(String.class), containsString("\"number\":1"));

    // The admin route is management-only, which is why the till could never print this before.
    assertThat(
        getAs("/admin/orders/" + orderId + "/fiscal-receipt", T, "CASHIER").getStatus(), is(403));

    // A customer who did not buy it gets the same 404 as an order that does not exist.
    assertThat(getAs("/orders/" + orderId + "/fiscal-receipt", T, "CUSTOMER").getStatus(), is(404));
  }

  // ── the audit ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An unbroken series audits as intact, with no gaps listed")
  void intactSeries() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    sell(store, "2.00");
    sell(store, "3.00");

    String body = audit(store);
    assertThat(body, containsString("\"intact\":true"));
    assertThat(body, containsString("\"gaps\":[]"));
    assertThat(body, containsString("\"issued\":3"));
    assertThat(body, containsString("\"expected\":3"));
  }

  @Test
  @DisplayName("A hole is found, and reported with its range rather than a count")
  void aHoleIsFound() throws Exception {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String gone = sell(store, "2.00");
    String alsoGone = sell(store, "3.00");
    sell(store, "4.00");

    // Reach past the API and delete two rows — the thing the API will not let anyone do, done
    // directly, so the audit has something real to find.
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st =
            c.prepareStatement("DELETE FROM \"order\".fiscal_receipts WHERE order_id = ANY (?)")) {
      st.setArray(
          1,
          c.createArrayOf("uuid", new Object[] {UUID.fromString(gone), UUID.fromString(alsoGone)}));
      st.executeUpdate();
    }

    String body = audit(store);
    assertThat(body, containsString("\"intact\":false"));
    // Contiguous holes are one gap with a range, not two findings — "2 to 3" is what gets
    // explained to an inspector.
    assertThat(body, containsString("\"from\":2"));
    assertThat(body, containsString("\"to\":3"));
    assertThat(body, containsString("\"issued\":2"));
    assertThat(body, containsString("\"expected\":4"));
  }

  @Test
  @DisplayName("The audit is scoped to one tenant's store")
  void auditIsTenantScoped() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String other =
        get(
                "/admin/fiscal-receipts/audit",
                "01a090ae-611e-701d-9d60-a9d7516ed03b",
                "storeId",
                store,
                "period",
                String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear()))
            .readEntity(String.class);
    assertThat(other, containsString("\"issued\":0"));
    assertThat(other, containsString("\"intact\":true"));
  }

  // ── the property the whole design exists for ───────────────────────────────

  @Test
  @DisplayName("Eight tills selling at once produce eight consecutive numbers, no duplicates")
  void concurrentTillsDoNotCollide() throws Exception {
    String store = Ids.newId().toString();
    int tills = 8;

    List<Callable<String>> work = new ArrayList<>();
    for (int i = 0; i < tills; i++) {
      final int n = i;
      work.add(() -> sell(store, "1.0" + n));
    }

    List<String> orders = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(tills)) {
      for (var f : pool.invokeAll(work)) {
        orders.add(f.get());
      }
    }

    var numbers = orders.stream().map(this::numberOf).sorted().toList();
    // This is the trade the design makes: the counter row serialises concurrent tills, which a
    // SEQUENCE would not have to do — and a SEQUENCE would gap on the first aborted transaction.
    assertThat(numbers.size(), is(tills));
    assertThat(numbers.get(0), is(1L));
    assertThat(numbers.get(tills - 1), is((long) tills));
    assertThat("duplicate numbers issued", numbers.stream().distinct().count(), is((long) tills));
    assertThat(audit(store), containsString("\"intact\":true"));
    assertThat(audit(store), not(containsString("\"from\"")));
  }
}
