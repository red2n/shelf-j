package com.storeql.order.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.EInvoiceTransports;
import com.storeql.order.einvoice.EInvoiceTransport.Dispatch;
import com.storeql.order.einvoice.EInvoiceTransport.Outbound;
import com.storeql.order.einvoice.EInvoiceTransport.Outcome;
import com.storeql.order.einvoice.EInvoiceTransport.TransportException;
import com.storeql.order.support.Checks;
import com.storeql.test.JsonStub;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The access point's facade, answered by a stub: what each of its answers becomes. */
class PeppolAccessPointTransportTest {

  private static JsonStub ap;
  private static PeppolAccessPointTransport transport;

  @BeforeAll
  static void start() {
    ap = JsonStub.start();
    ap.on("POST", "/documents", PeppolAccessPointTransportTest::answer)
        .on("GET", "/documents/D-OK", 200, "{\"id\":\"D-OK\",\"status\":\"delivered\"}")
        .on(
            "GET",
            "/documents/D-WAIT",
            200,
            "{\"id\":\"D-WAIT\",\"status\":\"in_transit\",\"reason\":\"on its way\"}")
        .on(
            "GET",
            "/documents/D-BAD",
            200,
            "{\"id\":\"D-BAD\",\"status\":\"failed\",\"reason\":\"receiver unreachable\"}")
        .on("GET", "/documents/D-DOWN", 503, "{\"message\":\"down\"}")
        // What a readiness check asks: does the access point know this participant?
        .on("GET", "/participants/0088:5790000435975", 200, "{\"participant\":\"known\"}")
        .on("GET", "/participants/9932:NOKEY", 401, "{\"message\":\"bad key\"}")
        .on("GET", "/participants/9932:FORBIDDEN", 403, "{\"message\":\"not you\"}")
        .on("GET", "/participants/9932:UNKNOWN", 404, "{\"message\":\"no such participant\"}")
        .on("GET", "/participants/9932:DOWN", 503, "{\"message\":\"maintenance\"}");
    transport = PeppolAccessPointTransport.forTest(ap.baseUrl(), "key");
  }

  @AfterAll
  static void stop() {
    ap.close();
  }

  /** The stub answers by the receiver: the document's own contents decide. */
  private static JsonStub.Answer answer(JsonStub.Call call) {
    String body = call.body();
    if (body.contains("\"receiver\":\"9932:DOWN\"")) {
      return new JsonStub.Answer(503, "{\"message\":\"maintenance\"}");
    }
    if (body.contains("\"receiver\":\"9932:NOBODY\"")) {
      return new JsonStub.Answer(422, "{\"message\":\"receiver not registered\"}");
    }
    if (body.contains("\"receiver\":\"9932:SLOW\"")) {
      return new JsonStub.Answer(202, "{\"id\":\"D-WAIT\",\"status\":\"pending\"}");
    }
    if (body.contains("\"receiver\":\"9932:NOID\"")) {
      return new JsonStub.Answer(201, "{\"status\":\"delivered\"}");
    }
    return new JsonStub.Answer(201, "{\"id\":\"D-OK\",\"status\":\"delivered\"}");
  }

  private static Outbound to(String receiver, String kind) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        kind,
        "INV/2026/000007",
        "0088:5790000435975",
        receiver,
        "<Invoice>7</Invoice>",
        null,
        "GB123456789",
        "LE-1",
        null);
  }

  /** What the service hands a check: the sender, and nothing to send. */
  private static Outbound from(String sender) {
    return Checks.credentials(sender, "GB123456789", null, null);
  }

  /** How many documents the access point has been handed. */
  private static int posts() {
    int posts = 0;
    for (JsonStub.Call call : ap.calls()) {
      if ("POST".equals(call.method())) posts++;
    }
    return posts;
  }

  private static String lastBody() {
    return ap.calls().get(ap.calls().size() - 1).body();
  }

  @Test
  void aDocumentIsPostedWithItsIdentifiersAndTheAnswerBecomesTheOutcome() {
    Dispatch d = transport.send(to("9932:GB555555555", "Invoice"));
    assertEquals("D-OK", d.providerRef());
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, d.outcome().state());
    String posted = lastBody();
    assertTrue(posted.contains("\"sender\":\"0088:5790000435975\""));
    assertTrue(posted.contains(PeppolAccessPointTransport.INVOICE_TYPE_ID));
    assertTrue(posted.contains(PeppolAccessPointTransport.PROCESS_ID));
    String document =
        Base64.getEncoder().encodeToString("<Invoice>7</Invoice>".getBytes(StandardCharsets.UTF_8));
    assertTrue(posted.contains(document));
    Dispatch credit = transport.send(to("9932:GB555555555", "CreditNote"));
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, credit.outcome().state());
    assertTrue(lastBody().contains(PeppolAccessPointTransport.CREDIT_NOTE_TYPE_ID));
  }

  @Test
  void takenIsPendingRefusedIsRejectedAndDownIsAnExceptionToTryAgain() {
    Dispatch slow = transport.send(to("9932:SLOW", "Invoice"));
    assertEquals("D-WAIT", slow.providerRef());
    assertEquals(EInvoiceTransports.STATUS_PENDING, slow.outcome().state());
    Dispatch nobody = transport.send(to("9932:NOBODY", "Invoice"));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, nobody.outcome().state());
    assertEquals("receiver not registered", nobody.outcome().detail());
    assertThrows(TransportException.class, () -> transport.send(to("9932:DOWN", "Invoice")));
    assertThrows(TransportException.class, () -> transport.send(to("9932:NOID", "Invoice")));
  }

  @Test
  void askingAfterADocumentReadsItsState() {
    Outbound d = to("9932:GB555555555", "Invoice");
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, transport.status(d, "D-OK").state());
    Outcome waiting = transport.status(d, "D-WAIT");
    assertEquals(EInvoiceTransports.STATUS_PENDING, waiting.state());
    assertEquals("on its way", waiting.detail());
    Outcome failed = transport.status(d, "D-BAD");
    assertEquals(EInvoiceTransports.STATUS_REJECTED, failed.state());
    assertEquals("receiver unreachable", failed.detail());
    assertEquals(EInvoiceTransports.STATUS_REJECTED, transport.status(d, "D-GONE").state());
    assertThrows(TransportException.class, () -> transport.status(d, "D-DOWN"));
  }

  @Test
  void aCheckAsksTheAccessPointAndTellsAWrongKeyFromANetworkThatIsDown() {
    int postsBefore = posts();
    EInvoiceTransport.Readiness ready = transport.check(from("0088:5790000435975"));
    assertEquals(EInvoiceTransport.Readiness.READY, ready.state());
    assertTrue(ready.detail().contains("took the key we hold"));

    // The distinction the whole check exists for: a key someone must fix, against a wait.
    assertEquals(EInvoiceTransport.Readiness.REFUSED, transport.check(from("9932:NOKEY")).state());
    assertEquals(
        EInvoiceTransport.Readiness.REFUSED, transport.check(from("9932:FORBIDDEN")).state());
    EInvoiceTransport.Readiness down = transport.check(from("9932:DOWN"));
    assertEquals(EInvoiceTransport.Readiness.UNREACHABLE, down.state());
    assertTrue(down.detail().contains("503"));

    // A probe path this access point does not serve still proves it is there and talking to us.
    assertEquals(EInvoiceTransport.Readiness.READY, transport.check(from("9932:UNKNOWN")).state());

    // Nothing is sent by a check, whatever it answered.
    assertEquals(postsBefore, posts(), "a check must never send a document");
  }

  @Test
  void aBusinessWithNoElectronicAddressIsRefusedWithoutAskingTheNetwork() {
    EInvoiceTransport.Readiness none = transport.check(from(null));
    assertEquals(EInvoiceTransport.Readiness.REFUSED, none.state());
    assertTrue(none.detail().contains("no electronic address"));
    assertEquals(EInvoiceTransport.Readiness.REFUSED, transport.check(from("   ")).state());
    // And a deployment with no access point of its own says so rather than trying.
    EInvoiceTransport.Readiness undeployed =
        PeppolAccessPointTransport.forTest("", "").check(from("0088:5790000435975"));
    assertEquals(EInvoiceTransport.Readiness.REFUSED, undeployed.state());
    assertTrue(undeployed.detail().contains("no address or key"));
  }

  @Test
  void withoutCredentialsItIsDeployedButCannotBeChosen() {
    assertFalse(PeppolAccessPointTransport.forTest("", "").isConfigured());
    assertFalse(PeppolAccessPointTransport.forTest("http://x", "").isConfigured());
    assertTrue(transport.isConfigured());
    assertEquals("ACCESS_POINT", transport.name());
  }
}
