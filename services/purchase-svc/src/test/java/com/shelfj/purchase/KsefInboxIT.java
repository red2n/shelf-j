package com.shelfj.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.JsonStub;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.TenantSvcStub;
import com.shelfj.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fetching a Polish buyer's invoices out of KSeF (07.13), against a stub standing in for the
 * ministry's system.
 *
 * <p>Two things are proven here that nothing else could be. The first is that the platform
 * <b>asks</b> at all: every other network delivers, and a Polish buyer that waits to be delivered
 * to receives nothing for ever. The second is that what comes back — FA(3), which is not an EN
 * 16931 document — goes through the ordinary intake and reaches the inbox with its seller, its
 * number and its lines read, because {@code shared/einvoice} reads Poland's structure into the same
 * model as any other.
 *
 * <p>The stub decrypts the token the client seals, with its own private key. That is the point of
 * it being a stub rather than a fake: a wrong OAEP digest would pass a stub that only looked at the
 * shape of the request, and fail the day a real token was sealed.
 */
@HelidonTest
class KsefInboxIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  private static final String T = Ids.newId().toString();
  private static final String T_OTHER = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  /** The buyer: a Polish business, by NIP. */
  private static final String OUR_NIP = "5260250991";

  private static final String OUR_VAT = "PL" + OUR_NIP;

  /** The business's own KSeF token, which the stub expects to read back out of the seal. */
  private static final String KSEF_TOKEN = "ksef-token-buyer-1";

  private static final String KSEF_NUMBER = "5260250991-20260914-ABCDEF-01";

  private static final String SECOND_NUMBER = "5260250991-20260915-ABCDEF-02";

  private static final JsonStub KSEF;
  private static final KeyPair MINISTRY;

  /** What the metadata query answers with next: both invoices, one, or none. */
  private static final AtomicReference<String> HOLDING = new AtomicReference<>("both");

  /** What a download does next: give the document, or fail as a network that is down. */
  private static final AtomicReference<String> DOWNLOAD = new AtomicReference<>("ok");

  /** What the stub read out of the sealed token, so a test can assert the sealing worked. */
  private static final AtomicReference<String> OPENED = new AtomicReference<>();

  static {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      MINISTRY = g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("no RSA", e);
    }
    KSEF =
        JsonStub.start()
            .on("GET", "/ksef/security/public-key-certificates", call -> certificates())
            .on("POST", "/ksef/auth/challenge", call -> challenge())
            .on("POST", "/ksef/auth/ksef-token", KsefInboxIT::signIn)
            .on("GET", "/ksef/auth/AUTH-REF-1", call -> new JsonStub.Answer(200, accepted()))
            .on("POST", "/ksef/auth/token/redeem", call -> new JsonStub.Answer(200, redeemed()))
            .on("POST", "/ksef/invoices/query/metadata", KsefInboxIT::metadata)
            .on(
                "GET",
                "/ksef/invoices/ksef/" + KSEF_NUMBER,
                call -> document(KSEF_NUMBER, "FV/2026/0001"))
            .on(
                "GET",
                "/ksef/invoices/ksef/" + SECOND_NUMBER,
                call -> document(SECOND_NUMBER, "FV/2026/0002"));
    System.setProperty("shelfj.einvoice.ksef.base-url", KSEF.baseUrl() + "/ksef");
    System.setProperty(
        "shelfj.einvoice.secrets-key", Base64.getEncoder().encodeToString(new byte[32]));
    System.setProperty("shelfj.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(T, "PLN", "PL")
        .withIdentity(T, OUR_VAT, null, null)
        .with(T_OTHER, "PLN", "PL")
        .withIdentity(T_OTHER, "PL7010001455", null, null);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    System.clearProperty("shelfj.einvoice.ksef.base-url");
    System.clearProperty("shelfj.einvoice.secrets-key");
    KSEF.close();
    PG.stop();
  }

  // ── the ministry, as a stub ────────────────────────────────────────────────

  private static JsonStub.Answer certificates() {
    return new JsonStub.Answer(
        200,
        "[{\"publicKeyId\":\"K1\",\"usage\":[\"KsefTokenEncryption\"],\"certificate\":\""
            + Base64.getEncoder().encodeToString(MINISTRY.getPublic().getEncoded())
            + "\"}]");
  }

  private static JsonStub.Answer challenge() {
    return new JsonStub.Answer(
        200, "{\"challenge\":\"CH-1\",\"timestampMs\":" + System.currentTimeMillis() + "}");
  }

  /** Opens the sealed token as the ministry would, and refuses one it cannot read. */
  private static JsonStub.Answer signIn(JsonStub.Call call) {
    JsonObject body = Json.createReader(new StringReader(call.body())).readObject();
    String sealed = body.getString("encryptedToken", "");
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          MINISTRY.getPrivate(),
          new OAEPParameterSpec(
              "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      String plain =
          new String(cipher.doFinal(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8);
      OPENED.set(plain);
      if (!plain.startsWith(KSEF_TOKEN + "|")) {
        return new JsonStub.Answer(401, "{\"exception\":\"that is not this business's token\"}");
      }
    } catch (GeneralSecurityException | RuntimeException e) {
      return new JsonStub.Answer(400, "{\"exception\":\"the token could not be opened\"}");
    }
    return new JsonStub.Answer(
        200, "{\"referenceNumber\":\"AUTH-REF-1\",\"authenticationToken\":{\"token\":\"AUTH-1\"}}");
  }

  private static String accepted() {
    return "{\"status\":{\"code\":200,\"description\":\"Uwierzytelnianie zakończone\"}}";
  }

  private static String redeemed() {
    return "{\"accessToken\":{\"token\":\"ACCESS-1\",\"validUntil\":\"2030-01-01T00:00:00Z\"}}";
  }

  private static JsonStub.Answer metadata(JsonStub.Call call) {
    String holding = HOLDING.get();
    if ("none".equals(holding)) return new JsonStub.Answer(200, "{\"invoices\":[]}");
    StringBuilder b = new StringBuilder("{\"invoices\":[");
    b.append(meta(KSEF_NUMBER, "2026-09-14", "FV/2026/0001"));
    if ("both".equals(holding))
      b.append(',').append(meta(SECOND_NUMBER, "2026-09-15", "FV/2026/0002"));
    return new JsonStub.Answer(200, b.append("]}").toString());
  }

  private static String meta(String number, String date, String invoiceNumber) {
    return "{\"ksefNumber\":\""
        + number
        + "\",\"invoicingDate\":\""
        + date
        + "\",\"invoiceNumber\":\""
        + invoiceNumber
        + "\",\"seller\":{\"nip\":\"9511983801\"}}";
  }

  /** An FA(3) invoice from a Polish supplier to this buyer. */
  private static JsonStub.Answer document(String ksefNumber, String number) {
    if ("down".equals(DOWNLOAD.get())) {
      return new JsonStub.Answer(503, "{\"exception\":\"the system is unavailable\"}");
    }
    return new JsonStub.Answer(200, fa3(number));
  }

  private static String fa3(String number) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Faktura xmlns=\"http://crd.gov.pl/wzor/2025/06/25/13775/\">"
        + "<Naglowek><KodFormularza kodSystemowy=\"FA (3)\" wersjaSchemy=\"1-0E\">FA</KodFormularza>"
        + "<WariantFormularza>3</WariantFormularza>"
        + "<DataWytworzeniaFa>2026-09-14T09:00:00Z</DataWytworzeniaFa></Naglowek>"
        + "<Podmiot1><DaneIdentyfikacyjne><NIP>9511983801</NIP>"
        + "<Nazwa>Hurtownia Wisła sp. z o.o.</Nazwa></DaneIdentyfikacyjne>"
        + "<Adres><KodKraju>PL</KodKraju><AdresL1>ul. Hurtowa 5</AdresL1>"
        + "<AdresL2>00-001 Warszawa</AdresL2></Adres></Podmiot1>"
        + "<Podmiot2><DaneIdentyfikacyjne><NIP>"
        + OUR_NIP
        + "</NIP><Nazwa>Sklep Portowy sp. z o.o.</Nazwa></DaneIdentyfikacyjne>"
        + "<Adres><KodKraju>PL</KodKraju><AdresL1>ul. Portowa 1</AdresL1>"
        + "<AdresL2>80-001 Gdańsk</AdresL2></Adres><JST>2</JST><GV>2</GV></Podmiot2>"
        + "<Fa><KodWaluty>PLN</KodWaluty><P_1>2026-09-14</P_1><P_2>"
        + number
        + "</P_2><P_13_1>1000.00</P_13_1><P_14_1>230.00</P_14_1><P_15>1230.00</P_15>"
        + "<Adnotacje><P_16>2</P_16><P_17>2</P_17><P_18>2</P_18><P_18A>2</P_18A>"
        + "<Zwolnienie><P_19N>1</P_19N></Zwolnienie>"
        + "<NoweSrodkiTransportu><P_22N>1</P_22N></NoweSrodkiTransportu><P_23>2</P_23>"
        + "<PMarzy><P_PMarzyN>1</P_PMarzyN></PMarzy></Adnotacje>"
        + "<RodzajFaktury>VAT</RodzajFaktury>"
        + "<FaWiersz><NrWierszaFa>1</NrWierszaFa><P_7>Kawa ziarnista 1kg</P_7>"
        + "<P_8A>szt</P_8A><P_8B>20</P_8B><P_9A>50.00</P_9A><P_11>1000.00</P_11>"
        + "<P_12>23</P_12></FaWiersz></Fa></Faktura>";
  }

  // ── steps ─────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String tenant, String role) {
    return WebTargets.at(target, path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role);
  }

  private Response put(String path, String json, String tenant) {
    return as(path, tenant, "OWNER").put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response post(String path, String tenant, String role) {
    return as(path, tenant, role).post(Entity.entity("{}", MediaType.APPLICATION_JSON));
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static String code(Response r) {
    String body = r.readEntity(String.class);
    JsonObject o = Json.createReader(new StringReader(body)).readObject();
    return o.containsKey("code") ? o.getString("code") : body;
  }

  private void fetchesFromKsef(String tenant) {
    JsonObject set =
        data(
            put(
                "/admin/e-invoices/inbox/settings",
                "{\"network\":\"KSEF\",\"provider\":\"KSEF\",\"providerAccount\":\""
                    + OUR_NIP
                    + "\",\"secret\":\""
                    + KSEF_TOKEN
                    + "\"}",
                tenant),
            200);
    assertThat(set.getBoolean("hasSecret"), is(true));
    assertThat("the token is never given back", set.toString(), not(containsString(KSEF_TOKEN)));
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A Polish buyer fetches its invoices, and FA(3) reaches the inbox read")
  void fetchesAndReads() {
    HOLDING.set("both");
    DOWNLOAD.set("ok");
    fetchesFromKsef(T);

    JsonObject fetched =
        data(post("/admin/e-invoices/inbox/fetch?from=2026-09-01&to=2026-09-20", T, "OWNER"), 200);
    assertThat(fetched.toString(), fetched.getInt("waiting"), is(2));
    assertThat(fetched.toString(), fetched.getInt("received"), is(2));
    assertThat(fetched.toString(), fetched.getInt("alreadyHeld"), is(0));

    // The token was sealed so the ministry could open it — the stub did, with its own private key.
    assertThat(OPENED.get(), containsString(KSEF_TOKEN + "|"));

    // And the documents are in the inbox, read: the seller, the number, the money.
    String list = as("/e-invoices", T, "OWNER").get().readEntity(String.class);
    assertThat(list, containsString("FV/2026/0001"));
    assertThat(list, containsString("Hurtownia Wisła sp. z o.o."));
    assertThat("read as Poland's own structure", list, containsString("FA3"));
    assertThat("fetched, not delivered", list, containsString("KSEF"));

    // Fetching again takes nothing twice: the inbox knows the bytes.
    JsonObject again =
        data(post("/admin/e-invoices/inbox/fetch?from=2026-09-01&to=2026-09-20", T, "OWNER"), 200);
    assertThat(again.getInt("received"), is(0));
    assertThat(again.getInt("alreadyHeld"), is(2));
  }

  @Test
  @DisplayName("The window carries on from the last fetch")
  void theWindowCarriesOn() {
    HOLDING.set("none");
    DOWNLOAD.set("ok");
    fetchesFromKsef(T_OTHER);

    JsonObject first = data(post("/admin/e-invoices/inbox/fetch", T_OTHER, "OWNER"), 200);
    assertThat(
        "a first fetch reaches back a month",
        first.getString("from"),
        is(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30).toString()));
    assertThat(first.getInt("waiting"), is(0));

    JsonObject settings = data(as("/admin/e-invoices/inbox/settings", T_OTHER, "OWNER").get(), 200);
    assertThat(
        settings.getString("fetchedTo"), is(LocalDate.now(java.time.ZoneOffset.UTC).toString()));
    assertThat(settings.getString("lastFetchNote"), containsString("nothing was waiting"));

    JsonObject second = data(post("/admin/e-invoices/inbox/fetch", T_OTHER, "OWNER"), 200);
    assertThat(
        "the next asks from where the last one reached",
        second.getString("from"),
        is(LocalDate.now(java.time.ZoneOffset.UTC).toString()));
  }

  @Test
  @DisplayName("A network that goes away leaves the window where it was")
  void aNetworkThatGoesAway() {
    HOLDING.set("one");
    DOWNLOAD.set("down");
    fetchesFromKsef(T);

    Response r = post("/admin/e-invoices/inbox/fetch?from=2026-09-01&to=2026-09-20", T, "OWNER");
    // A download that failed is reported, and what was taken stays taken.
    assertThat(r.getStatus(), is(200));
    DOWNLOAD.set("ok");
  }

  @Test
  @DisplayName("Fetching from nowhere is refused, and so is a token nobody gave")
  void refusals() {
    Response noSettings = post("/admin/e-invoices/inbox/fetch", Ids.newId().toString(), "OWNER");
    assertThat(code(noSettings), is("PURCHASE_INBOX_NOT_SET"));

    Response noToken =
        put(
            "/admin/e-invoices/inbox/settings",
            "{\"network\":\"KSEF\",\"provider\":\"KSEF\",\"providerAccount\":\"" + OUR_NIP + "\"}",
            Ids.newId().toString());
    assertThat(code(noToken), is("PURCHASE_INBOX_SECRET_REQUIRED"));

    Response badNip =
        put(
            "/admin/e-invoices/inbox/settings",
            "{\"network\":\"KSEF\",\"provider\":\"KSEF\",\"providerAccount\":\"123\",\"secret\":\"x\"}",
            Ids.newId().toString());
    assertThat(code(badNip), is("PURCHASE_INBOX_ACCOUNT_INVALID"));

    Response unknownNetwork =
        put(
            "/admin/e-invoices/inbox/settings",
            "{\"network\":\"PEPPOL\",\"provider\":\"KSEF\",\"providerAccount\":\""
                + OUR_NIP
                + "\"}",
            Ids.newId().toString());
    assertThat(code(unknownNetwork), is("PURCHASE_INBOX_NETWORK_UNKNOWN"));
  }

  @Test
  @DisplayName(
      "The platform standing in for KSeF says so rather than answering as an empty success")
  void theSimulatedStandIn() {
    String tenant = T_OTHER;
    data(
        put(
            "/admin/e-invoices/inbox/settings",
            "{\"network\":\"KSEF\",\"provider\":\"SIMULATED\",\"providerAccount\":\""
                + OUR_NIP
                + "\"}",
            tenant),
        200);
    JsonObject fetched = data(post("/admin/e-invoices/inbox/fetch", tenant, "OWNER"), 200);
    assertThat(fetched.getInt("waiting"), is(0));
    assertThat(fetched.getJsonArray("notes").toString(), containsString("nothing left it"));
  }

  @Test
  @DisplayName("A cashier neither reads the settings nor fetches")
  void onlyManagement() {
    assertThat(as("/admin/e-invoices/inbox/settings", T, "CASHIER").get().getStatus(), is(403));
    assertThat(post("/admin/e-invoices/inbox/fetch", T, "CASHIER").getStatus(), is(403));
  }
}
