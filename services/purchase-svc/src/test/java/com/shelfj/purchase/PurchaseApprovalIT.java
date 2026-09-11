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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Purchase order approval with spend authority limits, end to end.
 *
 * <p>Separate from {@link PurchaseIT} because the two need opposite configuration, and both states
 * matter: with no limits configured, approval is <b>off</b> and submission behaves exactly as it
 * did before this feature — which is what every existing test in PurchaseIT still proves. This
 * class configures limits and proves the control works.
 *
 * <p>The limits below are set up the way a real multi-currency tenant would: roughly equivalent
 * authorities per market rather than one number reused, because one number reused is the defect
 * this feature was designed around.
 */
@HelidonTest
class PurchaseApprovalIT {

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
    // A storekeeper may top up shelf stock; a manager runs the store's buying; the owner is
    // unlimited. Sterling, yen and rupee are configured — the dollar deliberately is NOT, so the
    // fail-closed path has something real to be tested against.
    System.setProperty(
        "shelfj.purchase.approval.limits",
        "GBP:STOREKEEPER:500,GBP:MANAGER:5000,GBP:OWNER:UNLIMITED,"
            + "JPY:MANAGER:800000,JPY:OWNER:UNLIMITED,"
            + "INR:MANAGER:500000,INR:OWNER:UNLIMITED");
  }

  private static final String T = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String STORE_A = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String VARIANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
  private static final String BUYER = "11111111-1111-1111-1111-111111111111";
  private static final String BOSS = "22222222-2222-2222-2222-222222222222";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
    System.clearProperty("shelfj.purchase.approval.limits");
  }

  @BeforeEach
  void truncate() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE purchase.purchase_order_approvals, purchase.goods_receipt_lines,"
              + " purchase.goods_receipts, purchase.purchase_order_lines,"
              + " purchase.purchase_orders, purchase.suppliers, purchase.outbox CASCADE");
    }
  }

  // ── the control ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An order inside the submitter's authority goes straight to the supplier")
  void withinAuthoritySubmitsDirectly() {
    String po = order("Brighton Provisions", "GBP", "100", "4.00"); // £400, under £500
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"SUBMITTED\""));
    assertThat(body, not(containsString("PENDING_APPROVAL")));
  }

  @Test
  @DisplayName("An order above it is held for approval, and the reason names both figures")
  void aboveAuthorityIsHeld() {
    String po = order("Big Order Ltd", "GBP", "1000", "4.00"); // £4,000, over a storekeeper's £500
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"status\":\"PENDING_APPROVAL\""));

    String trail =
        get("/purchase-orders/" + po + "/approvals", "STOREKEEPER", BUYER).readEntity(String.class);
    assertThat(trail, containsString("\"decision\":\"REQUESTED\""));
    // The figure the decision was made against is captured, not re-derived later.
    assertThat(trail, containsString("\"totalNet\":4000.00"));
    assertThat(trail, containsString("\"authority\":500"));
  }

  @Test
  @DisplayName("A manager approves it, and the order goes to the supplier naming who agreed")
  void approvalReleasesTheOrder() {
    String po = order("Approvable Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    Response r = post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"SUBMITTED\""));
    assertThat(body, containsString("\"approvedBy\":\"" + BOSS + "\""));
    assertThat(body, containsString("\"createdBy\":\"" + BUYER + "\""));
  }

  @Test
  @DisplayName("Separation of duties falls out of the limits: the submitter cannot self-approve")
  void theSubmitterCannotApproveTheirOwnOrder() {
    // The order is only here because it exceeded this person's ceiling, so the same check that
    // routed it refuses their approval. No separate "no self-approval" rule is needed — and a
    // separate rule would deadlock a one-person shop, where the owner legitimately does both.
    String po = order("Self Approval Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    Response r = post("/purchase-orders/" + po + "/approve", "{}", "STOREKEEPER", BUYER);
    assertThat(r.getStatus(), is(403));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_APPROVAL_EXCEEDS_AUTHORITY"));
  }

  @Test
  @DisplayName("An approver whose own ceiling is too low is refused as well")
  void approverNeedsEnoughAuthorityToo() {
    String po = order("Very Big Order Ltd", "GBP", "10000", "4.00"); // £40,000, over a manager
    post("/purchase-orders/" + po + "/submit", "{}", "MANAGER", BUYER);

    assertThat(
        post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS).getStatus(), is(403));
    // The owner is unlimited, so they can.
    assertThat(
        post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS).getStatus(), is(200));
  }

  // ── rejection ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A rejection returns the order to DRAFT with its reason, and must give one")
  void rejectionReturnsToDraft() {
    String po = order("Rejectable Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    // A rejection without a reason leaves the buyer no idea what to change.
    Response noReason = post("/purchase-orders/" + po + "/reject", "{}", "MANAGER", BOSS);
    assertThat(noReason.getStatus(), is(400));
    assertThat(
        noReason.readEntity(String.class), containsString("PURCHASE_APPROVAL_REASON_REQUIRED"));

    Response r =
        post(
            "/purchase-orders/" + po + "/reject",
            "{\"reason\":\"Get a second quote first\"}",
            "MANAGER",
            BOSS);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"status\":\"DRAFT\""));

    String trail =
        get("/purchase-orders/" + po + "/approvals", "MANAGER", BOSS).readEntity(String.class);
    assertThat(trail, containsString("\"decision\":\"REJECTED\""));
    assertThat(trail, containsString("Get a second quote first"));
  }

  @Test
  @DisplayName("The trail is append-only, so a resubmitted order keeps every earlier decision")
  void theTrailKeepsEveryCycle() {
    String po = order("Cycling Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);
    post("/purchase-orders/" + po + "/reject", "{\"reason\":\"too much\"}", "MANAGER", BOSS);
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);
    post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS);

    String trail =
        get("/purchase-orders/" + po + "/approvals", "MANAGER", BOSS).readEntity(String.class);
    // Two submissions, one rejection, one approval — a trail keeping only the last decision would
    // report an order that was simply approved, which is not what happened.
    assertThat(countOf(trail, "\"decision\":\"REQUESTED\""), is(2));
    assertThat(countOf(trail, "\"decision\":\"REJECTED\""), is(1));
    assertThat(countOf(trail, "\"decision\":\"APPROVED\""), is(1));
  }

  @Test
  @DisplayName("An order not awaiting a decision cannot be approved")
  void cannotApproveWhatIsNotPending() {
    String po = order("Draft Ltd", "GBP", "10", "1.00");
    assertThat(
        post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS).getStatus(), is(409));
  }

  // ── multi-currency, which is what makes the ceiling meaningful ──────────────

  @Test
  @DisplayName("A yen order is measured against the yen ceiling, not sterling's")
  void yenIsMeasuredAgainstTheYenCeiling() {
    // ¥700,000 is roughly £3,600 — inside a manager's ¥800,000 authority.
    String inside = order("Tokyo Trading KK", "JPY", "700", "1000");
    assertThat(
        post("/purchase-orders/" + inside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("\"status\":\"SUBMITTED\""));

    // ¥900,000 is not. Under a single sterling ceiling of 5,000 both of these would have been
    // held — the control would have been useless in Japan rather than merely wrong.
    String outside = order("Osaka Trading KK", "JPY", "900", "1000");
    assertThat(
        post("/purchase-orders/" + outside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("\"status\":\"PENDING_APPROVAL\""));
  }

  @Test
  @DisplayName("The same NUMBER that passes in yen is held in sterling")
  void theSameNumberMeansDifferentThingsPerCurrency() {
    String jpy = order("Yen Supplier KK", "JPY", "700", "1000"); // ¥700,000 — fine
    String gbp = order("Pound Supplier Ltd", "GBP", "700", "1000"); // £700,000 — not

    assertThat(
        post("/purchase-orders/" + jpy + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("SUBMITTED"));
    assertThat(
        post("/purchase-orders/" + gbp + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("PENDING_APPROVAL"));
  }

  @Test
  @DisplayName("India: a rupee order is measured against the rupee ceiling")
  void rupeeCeiling() {
    String inside = order("Mumbai Supplies Pvt", "INR", "1000", "400"); // ₹400,000
    assertThat(
        post("/purchase-orders/" + inside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("SUBMITTED"));
  }

  @Test
  @DisplayName("A currency nobody configured fails CLOSED — even for an unlimited owner")
  void unconfiguredCurrencyIsHeld() {
    // The dollar is deliberately absent from the limit table. The first order from a market nobody
    // set up is exactly the order nobody reviewed, so it must not be the one that sails through.
    String po = order("US Wholesale Inc", "USD", "1", "1.00"); // $1
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "OWNER", BUYER);

    assertThat(r.readEntity(String.class), containsString("\"status\":\"PENDING_APPROVAL\""));
    // And nobody can approve it either, which is the correct outcome: the answer is to configure
    // the dollar, not to let one person quietly decide it does not need configuring.
    Response approve = post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS);
    assertThat(approve.getStatus(), is(403));
    assertThat(approve.readEntity(String.class), containsString("no purchase authority"));
  }

  // ── discoverability ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A buyer can ask what they may commit before building the order")
  void spendAuthorityIsDiscoverable() {
    String gbp =
        get("/purchase-orders/spend-authority?currency=GBP", "STOREKEEPER", BUYER)
            .readEntity(String.class);
    assertThat(gbp, containsString("\"ceiling\":500"));
    assertThat(gbp, containsString("\"role\":\"STOREKEEPER\""));

    String jpy =
        get("/purchase-orders/spend-authority?currency=JPY", "MANAGER", BUYER)
            .readEntity(String.class);
    assertThat(jpy, containsString("\"ceiling\":800000"));

    String unlimited =
        get("/purchase-orders/spend-authority?currency=GBP", "OWNER", BOSS)
            .readEntity(String.class);
    assertThat(unlimited, containsString("\"unlimited\":true"));

    // An unconfigured currency says so rather than implying an unlimited ceiling by omission.
    String usd =
        get("/purchase-orders/spend-authority?currency=USD", "OWNER", BOSS)
            .readEntity(String.class);
    assertThat(usd, containsString("no purchase authority is configured for USD"));

    assertThat(
        get("/purchase-orders/spend-authority?currency=POUNDS", "OWNER", BOSS).getStatus(),
        is(400));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A supplier in {@code currency} with one line, returning the draft order's id. */
  private String order(String supplierName, String currency, String qty, String unitPrice) {
    Response sup =
        post(
            "/suppliers",
            "{\"name\":\"" + supplierName + "\",\"currency\":\"" + currency + "\"}",
            "OWNER",
            BOSS);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));

    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\"" + supId + "\",\"storeId\":\"" + STORE_A + "\"}",
            "OWNER",
            BUYER);
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));

    Response line =
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\""
                + VARIANT
                + "\",\"qty\":"
                + qty
                + ",\"unitPrice\":"
                + unitPrice
                + "}",
            "OWNER",
            BUYER);
    assertThat(line.getStatus(), is(201));
    return poId;
  }

  private Response post(String path, String json, String roles, String userId) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", roles)
        .header("X-User-Id", userId)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String roles, String userId) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", roles)
        .header("X-User-Id", userId)
        .get();
  }

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    return json.substring(start, json.indexOf("\"", start));
  }

  private static int countOf(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
    return n;
  }
}
