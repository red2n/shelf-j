package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.JsonStub;
import com.shelfj.test.JsonStub.Answer;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deposit return (09.16). A German business's till puts the Pfand on each container the scheme
 * takes back as its own line beside the drink, taxed as the drink; a British till charges none
 * before the UK scheme starts; empties brought back are paid out once per till key at the scheme's
 * amount, and the period's report shows charged, refunded and unredeemed by material. The catalogue
 * stands in a stub that names the drink's container; the schemes come from tenant-svc's register,
 * stubbed here.
 */
@HelidonTest
class DepositIT {

  private static final String T_DE = Ids.newId().toString();
  private static final String T_GB = Ids.newId().toString();
  private static final String S_DE = Ids.newId().toString();
  private static final String S_GB = Ids.newId().toString();
  private static final String COLA = Ids.newId().toString();
  private static final String CRISPS = Ids.newId().toString();
  private static final String CASHIER = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final JsonStub PRODUCTS;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T_DE, "EUR", "DE")
        .with(T_GB, "GBP", "GB")
        .withDepositScheme(
            "DE",
            "DE",
            "EUR",
            "0.25",
            "PET,ALUMINIUM,STEEL,GLASS",
            100,
            3000,
            "STANDARD",
            "2003-01-01",
            "VerpackG §31")
        .withDepositScheme(
            "GB",
            "GB",
            "GBP",
            "0.20",
            "PET,ALUMINIUM,STEEL",
            150,
            3000,
            "OUTSIDE_SCOPE",
            "2027-10-01",
            "SI 2025/67");
    PRODUCTS =
        JsonStub.start("product-svc")
            .on(
                "GET",
                "/admin/products/variants/resolve",
                call ->
                    Answer.ok(
                        "[{\"variantId\":\""
                            + COLA
                            + "\",\"productName\":\"Cola\",\"sku\":\"COLA\",\"unit\":\"EA\","
                            + "\"depositMaterial\":\"PET\",\"depositVolumeMl\":500},"
                            + "{\"variantId\":\""
                            + CRISPS
                            + "\",\"productName\":\"Crisps\",\"sku\":\"CRISPS\",\"unit\":\"EA\"}]"));
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

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    PRODUCTS.close();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-User-Id", CASHIER);
  }

  private Response place(String tenant, String store, String variant, int qty) {
    return as("/orders", tenant, "CASHIER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(
            Entity.entity(
                "{\"storeId\":\""
                    + store
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"paymentMethod\":\"CASH\","
                    + "\"items\":[{\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + ",\"unitPrice\":1.50}]}",
                MediaType.APPLICATION_JSON));
  }

  private Response refund(String tenant, String store, String lines, String key, String roles) {
    Invocation.Builder b = as("/orders/container-refunds", tenant, roles);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(
        Entity.entity(
            "{\"storeId\":\""
                + store
                + "\",\"tillSessionId\":\""
                + Ids.newId()
                + "\",\"lines\":"
                + lines
                + "}",
            MediaType.APPLICATION_JSON));
  }

  private static String extractId(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private String report(String tenant, String roles, String from, String to) {
    return as("/admin/reports/deposits", tenant, roles, "from", from, "to", to)
        .get()
        .readEntity(String.class);
  }

  // ── the sale ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A German till puts the Pfand on each bottle as its own line, added to the total")
  void aGermanSaleCarriesThePfandAsItsOwnLine() {
    String placed = body(place(T_DE, S_DE, COLA, 3), 201);
    assertThat(placed, containsString("\"depositAmount\":0.75"));
    assertThat(placed, containsString("\"material\":\"PET\""));
    assertThat(placed, containsString("\"volumeMl\":500"));
    assertThat(placed, containsString("\"depositEach\":0.25"));
    assertThat(placed, containsString("\"vatTreatment\":\"STANDARD\""));
    assertThat(placed, containsString("\"schemeScope\":\"DE\""));
    assertThat("goods 4.50 plus the deposit", placed, containsString("\"total\":5.25"));
    assertThat("the subtotal is the goods alone", placed, containsString("\"subtotal\":4.50"));
    String read = body(as("/orders/" + extractId(placed), T_DE, "CASHIER").get(), 200);
    assertThat(read, containsString("\"depositAmount\":0.75"));
    assertThat(read, containsString("\"citation\":\"VerpackG §31\""));

    String crisps = body(place(T_DE, S_DE, CRISPS, 2), 201);
    assertThat("no container, no deposit", crisps, containsString("\"depositAmount\":0"));
    assertThat(crisps, containsString("\"deposits\":[]"));
  }

  @Test
  @DisplayName("A British till charges no deposit before the UK scheme starts")
  void aBritishSaleCarriesNoneBeforeTheSchemeStarts() {
    String placed = body(place(T_GB, S_GB, COLA, 2), 201);
    assertThat(placed, containsString("\"depositAmount\":0"));
    assertThat(placed, containsString("\"deposits\":[]"));
    assertThat(placed, containsString("\"total\":3.00"));
  }

  // ── empties come back ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Empties are paid out once per till key at the scheme's amount, and reported")
  void emptiesArePaidBackOnceAndReported() {
    body(place(T_DE, S_DE, COLA, 3), 201);
    String key = Ids.newId().toString();
    String lines =
        "[{\"material\":\"pet\",\"volumeMl\":500,\"count\":2},"
            + "{\"material\":\"ALUMINIUM\",\"volumeMl\":330,\"count\":1}]";
    String first = body(refund(T_DE, S_DE, lines, key, "CASHIER"), 201);
    assertThat(first, containsString("\"amount\":0.75"));
    assertThat(first, containsString("\"containers\":3"));
    assertThat(first, containsString("\"material\":\"PET\""));
    assertThat(first, containsString("\"schemeScope\":\"DE\""));
    String again = body(refund(T_DE, S_DE, lines, key, "CASHIER"), 201);
    assertThat("the same refund, not a second pay-out", extractId(again), is(extractId(first)));
    body(as("/orders/container-refunds/" + extractId(first), T_DE, "CASHIER").get(), 200);
    assertThat(
        "another business does not see it",
        as("/orders/container-refunds/" + extractId(first), T_GB, "OWNER").get().getStatus(),
        is(404));

    String from = Instant.now().minus(1, ChronoUnit.DAYS).toString();
    String to = Instant.now().plus(1, ChronoUnit.DAYS).toString();
    String rep = report(T_DE, "OWNER", from, to);
    assertThat(rep, containsString("\"refundedContainers\":3"));
    assertThat(rep, containsString("\"refundedAmount\":0.75"));
    assertThat(rep, containsString("\"currency\":\"EUR\""));
    assertThat(rep, containsString("\"material\":\"PET\""));
    assertThat(rep, containsString("\"material\":\"ALUMINIUM\""));
    assertThat(
        "a cashier does not read the report",
        as("/admin/reports/deposits", T_DE, "CASHIER", "from", from, "to", to).get().getStatus(),
        is(403));
    assertThat(
        "a period ending before it starts",
        as("/admin/reports/deposits", T_DE, "OWNER", "from", to, "to", from).get().getStatus(),
        is(400));
    assertThat(
        "the British business's report shows none of it",
        report(T_GB, "OWNER", from, to),
        containsString("\"refundedContainers\":0"));
  }

  @Test
  @DisplayName(
      "A container the scheme does not take back, no lines, too many, no key, or no scheme")
  void refundsAreRefusedWhereTheLawGivesNothingBack() {
    String jar =
        body(
            refund(
                T_DE,
                S_DE,
                "[{\"material\":\"GLASS\",\"volumeMl\":5000,\"count\":1}]",
                Ids.newId().toString(),
                "CASHIER"),
            400);
    assertThat(jar, containsString("ORDER_CONTAINER_NOT_IN_SCHEME"));
    String none = body(refund(T_DE, S_DE, "[]", Ids.newId().toString(), "CASHIER"), 400);
    assertThat(none, containsString("ORDER_CONTAINER_LINES_INVALID"));
    String many =
        body(
            refund(
                T_DE,
                S_DE,
                "[{\"material\":\"PET\",\"volumeMl\":500,\"count\":501}]",
                Ids.newId().toString(),
                "CASHIER"),
            400);
    assertThat(many, containsString("ORDER_CONTAINER_COUNT_TOO_MANY"));
    String nokey =
        body(
            refund(
                T_DE,
                S_DE,
                "[{\"material\":\"PET\",\"volumeMl\":500,\"count\":1}]",
                null,
                "CASHIER"),
            400);
    assertThat(nokey, containsString("MISSING_IDEMPOTENCY_KEY"));
    String britain =
        body(
            refund(
                T_GB,
                S_GB,
                "[{\"material\":\"ALUMINIUM\",\"volumeMl\":330,\"count\":1}]",
                Ids.newId().toString(),
                "CASHIER"),
            409);
    assertThat(britain, containsString("ORDER_DEPOSIT_SCHEME_NOT_IN_FORCE"));
    assertThat(
        "a shopper pays nothing out",
        refund(
                T_DE,
                S_DE,
                "[{\"material\":\"PET\",\"volumeMl\":500,\"count\":1}]",
                Ids.newId().toString(),
                "CUSTOMER")
            .getStatus(),
        is(403));
    assertThat(
        "nothing of it was recorded",
        report(
            T_DE,
            "OWNER",
            Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            Instant.now().plus(1, ChronoUnit.HOURS).toString()),
        not(containsString("\"material\":\"GLASS\"")));
  }
}
