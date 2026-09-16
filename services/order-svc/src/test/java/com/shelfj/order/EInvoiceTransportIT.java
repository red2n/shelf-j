package com.shelfj.order;

import static com.shelfj.order.support.InvoicingStubs.V_STD;
import static com.shelfj.order.support.InvoicingStubs.basket;
import static com.shelfj.order.support.InvoicingStubs.business;
import static com.shelfj.order.support.InvoicingStubs.code;
import static com.shelfj.order.support.InvoicingStubs.data;
import static com.shelfj.order.support.InvoicingStubs.dataArray;
import static com.shelfj.order.support.InvoicingStubs.envelope;
import static com.shelfj.order.support.InvoicingStubs.eventually;
import static com.shelfj.order.support.InvoicingStubs.only;
import static com.shelfj.order.support.InvoicingStubs.services;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import com.shelfj.ids.Ids;
import com.shelfj.order.support.Till;
import com.shelfj.test.Concurrency;
import com.shelfj.test.JsonStub;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The e-invoicing transport seam (07.13, 18.9): where a business's documents leave, and what the
 * network says. The simulated network answers at once; a stub stands where a Peppol access point's
 * facade would, and is told to deliver, take and answer later, refuse, or be down. The interesting
 * tests are the ones a network makes hard: a document sent exactly once however many ask, tried
 * again only while the network is unreachable, refused for good when it says no, and nothing sent
 * where nobody can receive it.
 */
@HelidonTest
class EInvoiceTransportIT {

  private static final String T = Ids.newId().toString();
  private static final String T_NOADDR = Ids.newId().toString();
  private static final String T_OTHER = Ids.newId().toString();
  private static final String S = Ids.newId().toString();
  private static final String S_NOADDR = Ids.newId().toString();
  private static final String S_OTHER = Ids.newId().toString();
  private static final String C_PEPPOL = Ids.newId().toString();
  private static final String C_REJECT = Ids.newId().toString();
  private static final String C_LATER = Ids.newId().toString();
  private static final String C_NOADDR = Ids.newId().toString();

  private static final String[] LEEDS = {"2 Mill Lane", "Leeds", "LS1 4AB"};

  /** What the access point stub does with the next document: deliver, accept, reject or down. */
  private static final AtomicReference<String> MODE = new AtomicReference<>("deliver");

  private static final AtomicInteger AP_POSTS = new AtomicInteger();
  private static final AtomicReference<String> LAST_POST = new AtomicReference<>();

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub SERVICES;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .withIdentity(T, "GB123456789", "0088", "5790000435975")
            .withLegalName(T, "Harbour Provisions Ltd")
            .withStore(T, S, "GB", "1 High Street", "London", "E1 6AN")
            .with(T_NOADDR, "GBP", "GB")
            .withIdentity(T_NOADDR, "GB987654321", null, null)
            .withLegalName(T_NOADDR, "Unaddressed Ltd")
            .withStore(T_NOADDR, S_NOADDR, "GB", "2 Low Street", "Bath", "BA1 1AA")
            .with(T_OTHER, "GBP", "GB")
            .withIdentity(T_OTHER, "GB111111111", "9932", "GB111111111")
            .withLegalName(T_OTHER, "Someone Else Ltd")
            .withStore(T_OTHER, S_OTHER, "GB", "1 Other Street", "Hull", "HU1 1AA")
            // As if the mandate were in force: what the settings suggest.
            .withObligation("GB", "E_INVOICING_B2B", "COUNTRY", "2020-01-01", null);
    SERVICES = services();
    business(
        SERVICES, C_PEPPOL, "Cafe Leeds Ltd", "GB555555555", "GB", "9932", "GB555555555", LEEDS);
    business(
        SERVICES, C_REJECT, "Nobody Ltd", "GB555555555", "GB", "9932", "GB555555555REJECT", LEEDS);
    business(SERVICES, C_LATER, "Slow Ltd", "GB555555555", "GB", "9932", "GB555555555LATER", LEEDS);
    business(SERVICES, C_NOADDR, "Offline Ltd", "GB222222222", "GB", null, null, LEEDS);
    // The access point's facade: one document id, answered as MODE says.
    SERVICES.on("POST", "/documents", EInvoiceTransportIT::accessPointSend);
    SERVICES.on("GET", "/documents/AP-1", EInvoiceTransportIT::accessPointStatus);
    System.setProperty("shelfj.einvoice.peppol.base-url", SERVICES.baseUrl());
    System.setProperty("shelfj.einvoice.peppol.api-key", "test-key");
    System.setProperty("shelfj.order.einvoice-transport.interval-seconds", "1");
    System.setProperty("shelfj.order.einvoice-transport.retry-base-seconds", "1");
    System.setProperty("shelfj.order.einvoice-transport.poll-seconds", "1");
    System.setProperty("shelfj.order.einvoice-transport.max-attempts", "4");
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "order");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.order.pricing.enforce", "true");
    System.setProperty("shelfj.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    for (String p :
        List.of(
            "shelfj.order.pricing.enforce",
            "shelfj.einvoice.peppol.base-url",
            "shelfj.einvoice.peppol.api-key",
            "shelfj.order.einvoice-transport.interval-seconds",
            "shelfj.order.einvoice-transport.retry-base-seconds",
            "shelfj.order.einvoice-transport.poll-seconds",
            "shelfj.order.einvoice-transport.max-attempts")) {
      System.clearProperty(p);
    }
    SERVICES.close();
    TENANTS.close();
    PG.stop();
  }

  private static JsonStub.Answer accessPointSend(JsonStub.Call call) {
    AP_POSTS.incrementAndGet();
    LAST_POST.set(call.body());
    return switch (MODE.get()) {
      case "accept" -> new JsonStub.Answer(202, "{\"id\":\"AP-1\",\"status\":\"PENDING\"}");
      case "reject" ->
          new JsonStub.Answer(422, "{\"message\":\"receiver not registered in the SMP\"}");
      case "down" -> new JsonStub.Answer(503, "{\"message\":\"maintenance\"}");
      default -> new JsonStub.Answer(201, "{\"id\":\"AP-1\",\"status\":\"DELIVERED\"}");
    };
  }

  private static JsonStub.Answer accessPointStatus(JsonStub.Call call) {
    return new JsonStub.Answer(200, "{\"id\":\"AP-1\",\"status\":\"DELIVERED\"}");
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Till till() {
    return new Till(target);
  }

  private Response setTransport(String tenant, String json) {
    return till().put("/admin/einvoicing/transport", json, tenant, "OWNER");
  }

  private static String transport(String network, String provider) {
    return "{\"network\":\""
        + network
        + "\""
        + (provider == null ? "" : ",\"provider\":\"" + provider + "\"")
        + "}";
  }

  /** Sells to the customer and waits for the invoice; the document with its newest transmission. */
  private JsonObject invoiced(String customer) {
    String order = till().sell(basket(S, customer, "GBP", V_STD, "1"), T);
    return only(till().documentsOf(order, T, 1), "INVOICE");
  }

  private JsonObject document(String id) {
    return data(till().get("/admin/sales-invoices/" + id, T));
  }

  /** The document's newest transmission once it is in one of the states. */
  private JsonObject transmissionOf(String id, String... states) {
    List<String> wanted = List.of(states);
    JsonObject t =
        eventually(
            () -> {
              JsonObject d = document(id);
              if (!d.containsKey("transmission") || d.isNull("transmission")) return null;
              JsonObject tr = d.getJsonObject("transmission");
              return wanted.contains(tr.getString("status")) ? tr : null;
            });
    assertThat("transmission of " + id + " in " + wanted, t, notNullValue());
    return t;
  }

  private Response send(String id, String tenant) {
    return till().post("/admin/sales-invoices/" + id + "/transmissions", "{}", tenant);
  }

  private JsonArray transmissions(String id) {
    return dataArray(till().get("/admin/sales-invoices/" + id + "/transmissions", T));
  }

  // ── settings ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Nothing is sent until a network is chosen; the settings say what is offered")
  void settings() {
    // A business that never chose: the other tests set T's network as they need it.
    Response r = till().get("/admin/einvoicing/transport", T_OTHER);
    assertThat(r.getStatus(), is(200));
    JsonObject s = data(r);
    assertThat(s.getString("network"), is("NONE"));
    assertThat(
        s.getJsonArray("networks").toString(), is("[\"PEPPOL\",\"FR_PDP\",\"KSEF\",\"IRP\"]"));
    assertThat(
        s.getJsonObject("providers").getJsonArray("PEPPOL").toString(),
        is("[\"ACCESS_POINT\",\"SIMULATED\"]"));
    assertThat(s.getJsonObject("providers").getJsonArray("KSEF").toString(), is("[\"SIMULATED\"]"));
    // The stub's credentials are configured, so the access point can be chosen here.
    assertThat(
        s.getJsonObject("available").getJsonArray("PEPPOL").toString(),
        containsString("ACCESS_POINT"));
    assertThat(s.getString("senderAddress"), is("9932:GB111111111"));
    assertThat(s.getString("suggestedNetwork"), is("PEPPOL"));
    assertThat(
        data(till().get("/admin/einvoicing/transport", T)).getString("senderAddress"),
        is("0088:5790000435975"));

    assertThat(till().getAs("/admin/einvoicing/transport", T, "CASHIER").getStatus(), is(403));
    assertThat(
        till()
            .put("/admin/einvoicing/transport", transport("PEPPOL", "SIMULATED"), T, "CASHIER")
            .getStatus(),
        is(403));
    Response unknown = setTransport(T, transport("FAX", "SIMULATED"));
    assertThat(unknown.getStatus(), is(400));
    assertThat(code(unknown), is("EINVOICE_NETWORK_UNKNOWN"));
    Response noProvider = setTransport(T, transport("PEPPOL", null));
    assertThat(noProvider.getStatus(), is(400));
    assertThat(code(noProvider), is("EINVOICE_PROVIDER_REQUIRED"));
    Response wrongProvider = setTransport(T, transport("KSEF", "ACCESS_POINT"));
    assertThat(wrongProvider.getStatus(), is(400));
    assertThat(code(wrongProvider), is("EINVOICE_PROVIDER_UNKNOWN"));
    Response noSender = setTransport(T_NOADDR, transport("PEPPOL", "SIMULATED"));
    assertThat(noSender.getStatus(), is(409));
    assertThat(code(noSender), is("EINVOICE_SENDER_ADDRESS_MISSING"));
    Response longAccount =
        setTransport(
            T,
            "{\"network\":\"PEPPOL\",\"provider\":\"SIMULATED\",\"providerAccount\":\""
                + "x".repeat(121)
                + "\"}");
    assertThat(longAccount.getStatus(), is(400));

    // KSeF with the simulated provider needs no address: the network takes the sender's own.
    Response ksef = setTransport(T_NOADDR, transport("KSEF", "SIMULATED"));
    assertThat(ksef.readEntity(String.class), ksef.getStatus(), is(200));
    Response none = setTransport(T_NOADDR, transport("NONE", null));
    assertThat(none.getStatus(), is(200));
    assertThat(data(none).getString("network"), is("NONE"));
  }

  // ── the simulated network ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Over the simulated network a document is delivered, refused, or taken and answered later")
  void simulated() {
    assertThat(setTransport(T, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));

    JsonObject inv = invoiced(C_PEPPOL);
    JsonObject sent = transmissionOf(inv.getString("id"), "ACCEPTED");
    assertThat(sent.getString("providerRef"), startsWith("SIM-"));
    assertThat(sent.getString("receiver"), is("9932:GB555555555"));
    assertThat(sent.getString("network"), is("PEPPOL"));
    assertThat(sent.getString("provider"), is("SIMULATED"));
    assertThat(sent.getString("sentAt"), notNullValue());
    Response again = send(inv.getString("id"), T);
    assertThat(again.getStatus(), is(409));
    assertThat(code(again), is("EINVOICE_ALREADY_SENT"));

    JsonObject refused = invoiced(C_REJECT);
    JsonObject rejected = transmissionOf(refused.getString("id"), "REJECTED");
    assertThat(rejected.getString("detail"), containsString("knows no participant"));
    // Refused is final until someone sends it again; the answer is the network's again.
    Response resent = send(refused.getString("id"), T);
    assertThat(resent.getStatus(), is(200));
    assertThat(data(resent).getString("status"), is("REJECTED"));
    assertThat(transmissions(refused.getString("id")), hasSize(2));

    JsonObject slow = invoiced(C_LATER);
    JsonObject delivered = transmissionOf(slow.getString("id"), "ACCEPTED");
    assertThat(
        "taken first, then asked after", delivered.getInt("attempts"), greaterThanOrEqualTo(2));

    // A buyer with no electronic address has nowhere to receive: nothing is queued, and a send by
    // hand says why.
    JsonObject offline = invoiced(C_NOADDR);
    Response nowhere = send(offline.getString("id"), T);
    assertThat(nowhere.getStatus(), is(409));
    assertThat(code(nowhere), is("EINVOICE_RECEIVER_ADDRESS_MISSING"));
    assertThat(document(offline.getString("id")).containsKey("transmission"), is(false));
  }

  // ── an access point ────────────────────────────────────────────────────────

  @Test
  @DisplayName("An access point that is down is tried again, later each time, until it answers")
  void accessPointRetried() {
    assertThat(
        setTransport(
                T,
                "{\"network\":\"PEPPOL\",\"provider\":\"ACCESS_POINT\",\"providerAccount\":\"LE-123\"}")
            .getStatus(),
        is(200));
    MODE.set("down");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      String id = inv.getString("id");
      JsonObject waiting =
          eventually(
              () -> {
                JsonObject d = document(id);
                if (!d.containsKey("transmission")) return null;
                JsonObject t = d.getJsonObject("transmission");
                return t.getInt("attempts") >= 2 ? t : null;
              });
      assertThat(waiting, notNullValue());
      assertThat(waiting.getString("detail"), containsString("trying again"));
      assertThat(waiting.getString("status"), is("QUEUED"));
      // Back up, but slow to deliver: taken, then asked after until it says delivered.
      MODE.set("accept");
      JsonObject done = transmissionOf(id, "ACCEPTED");
      assertThat(done.getString("providerRef"), is("AP-1"));
      assertThat(done.getInt("attempts"), greaterThanOrEqualTo(3));
      try (JsonReader r = Json.createReader(new StringReader(LAST_POST.get()))) {
        JsonObject posted = r.readObject();
        assertThat(posted.getString("sender"), is("0088:5790000435975"));
        assertThat(posted.getString("receiver"), is("9932:GB555555555"));
        assertThat(posted.getString("documentTypeId"), containsString("Invoice-2::Invoice"));
        assertThat(
            posted.getString("processId"), is("urn:fdc:peppol.eu:2017:poacc:billing:01:1.0"));
        String xml =
            new String(
                Base64.getDecoder().decode(posted.getString("document")), StandardCharsets.UTF_8);
        assertThat(xml, containsString(inv.getString("fullNumber")));
      }
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName(
      "The access point's refusal is final; sent again by hand, the answer comes back at once")
  void accessPointRefusal() {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("reject");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      JsonObject rejected = transmissionOf(inv.getString("id"), "REJECTED");
      assertThat(rejected.getString("detail"), is("receiver not registered in the SMP"));
      MODE.set("deliver");
      Response resent = send(inv.getString("id"), T);
      assertThat(resent.readEntity(String.class), resent.getStatus(), is(200));
      JsonObject t =
          data(till().get("/admin/sales-invoices/" + inv.getString("id"), T))
              .getJsonObject("transmission");
      assertThat(t.getString("status"), is("ACCEPTED"));
      assertThat(t.getString("providerRef"), is("AP-1"));
      assertThat(transmissions(inv.getString("id")), hasSize(2));
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName(
      "After the last attempt the document is marked failed, and can be sent again by hand")
  void gaveUp() {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("down");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      JsonObject failed = transmissionOf(inv.getString("id"), "FAILED");
      assertThat(failed.getString("detail"), containsString("gave up after 4 attempts"));
      MODE.set("deliver");
      Response resent = send(inv.getString("id"), T);
      assertThat(resent.getStatus(), is(200));
      assertThat(data(resent).getString("status"), is("ACCEPTED"));
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName("Eight sends at once for one document send it once")
  void eightAtOnce() throws Exception {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("reject");
    JsonObject inv;
    try {
      inv = invoiced(C_PEPPOL);
      transmissionOf(inv.getString("id"), "REJECTED");
    } finally {
      MODE.set("deliver");
    }
    int before = AP_POSTS.get();
    List<Response> all = Concurrency.inParallel(8, () -> send(inv.getString("id"), T));
    long ok = all.stream().filter(r -> r.getStatus() == 200).count();
    long refused = all.stream().filter(r -> r.getStatus() == 409).count();
    assertThat(ok, is(1L));
    assertThat(refused, is(7L));
    assertThat(AP_POSTS.get() - before, is(1));
    assertThat(transmissions(inv.getString("id")), hasSize(2));
  }

  // ── the outbox, and who sees it ────────────────────────────────────────────

  @Test
  @DisplayName("The outbox lists every attempt, filtered and paged; another tenant sees none of it")
  void outbox() {
    assertThat(setTransport(T, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));
    JsonObject inv = invoiced(C_PEPPOL);
    transmissionOf(inv.getString("id"), "ACCEPTED");
    Response accepted =
        till().get("/admin/einvoicing/transmissions", T, "status", "accepted", "limit", "1");
    assertThat(accepted.getStatus(), is(200));
    JsonObject env = envelope(accepted);
    assertThat(env.getJsonArray("data"), hasSize(1));
    assertThat(env.getJsonArray("data").getJsonObject(0).getString("status"), is("ACCEPTED"));
    assertThat(env.getJsonObject("meta").getString("nextCursor", null), notNullValue());
    Response unknown = till().get("/admin/einvoicing/transmissions", T, "status", "LOST");
    assertThat(unknown.getStatus(), is(400));
    assertThat(code(unknown), is("EINVOICE_TRANSMISSION_STATUS_UNKNOWN"));

    assertThat(dataArray(till().get("/admin/einvoicing/transmissions", T_OTHER)), hasSize(0));
    assertThat(
        till()
            .get("/admin/sales-invoices/" + inv.getString("id") + "/transmissions", T_OTHER)
            .getStatus(),
        is(404));
    assertThat(send(inv.getString("id"), T_OTHER).getStatus(), is(404));
  }

  @Test
  @DisplayName("With no network chosen a document is issued and kept, never sent")
  void none() throws Exception {
    assertThat(setTransport(T, transport("NONE", null)).getStatus(), is(200));
    JsonObject inv = invoiced(C_PEPPOL);
    Thread.sleep(2500);
    assertThat(document(inv.getString("id")).containsKey("transmission"), is(false));
    Response nothing = send(inv.getString("id"), T);
    assertThat(nothing.getStatus(), is(409));
    assertThat(code(nothing), is("EINVOICE_TRANSPORT_NOT_SET"));
    JsonObject after = data(till().get("/admin/einvoicing/transport", T));
    assertThat(!after.containsKey("provider") || after.isNull("provider"), is(true));
  }
}
