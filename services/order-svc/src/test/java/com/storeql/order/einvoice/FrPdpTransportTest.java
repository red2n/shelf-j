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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** France's platform, answered by a stub: the reform's statuses as the transport reads them. */
class FrPdpTransportTest {

  private static JsonStub pdp;
  private static FrPdpTransport transport;

  @BeforeAll
  static void start() {
    pdp = JsonStub.start();
    pdp.on("POST", "/invoices", FrPdpTransportTest::deposit)
        .on(
            "GET",
            "/invoices/F-1/lifecycle",
            200,
            "{\"status\":{\"code\":209,\"label\":\"Reçue par la plateforme\"}}")
        .on(
            "GET",
            "/invoices/F-2/lifecycle",
            200,
            "{\"status\":{\"code\":210,\"label\":\"Refusée\",\"reason\":\"bon de commande inconnu\"}}")
        .on(
            "GET",
            "/invoices/F-3/lifecycle",
            200,
            "{\"status\":{\"code\":\"213\",\"reason\":\"SIRET du destinataire inconnu\"}}")
        .on("GET", "/invoices/F-4/lifecycle", 200, "{\"code\":201}")
        .on("GET", "/invoices/F-5/lifecycle", 200, "{\"status\":{\"code\":212}}")
        .on("GET", "/invoices/F-DOWN/lifecycle", 503, "{}")
        // What a readiness check asks: is this taxpayer known to the platform?
        .on("GET", "/participants/123456789", 200, "{\"siren\":\"123456789\"}")
        .on("GET", "/participants/999999999", 403, "{\"message\":\"clé inconnue\"}")
        .on("GET", "/participants/888888888", 502, "{\"message\":\"passerelle\"}");
    transport = FrPdpTransport.forTest(pdp.baseUrl(), "key");
  }

  @AfterAll
  static void stop() {
    pdp.close();
  }

  private static JsonStub.Answer deposit(JsonStub.Call call) {
    String body = call.body();
    if (body.contains("\"recipient\":\"0009:DOWN\""))
      return new JsonStub.Answer(502, "{\"message\":\"gateway\"}");
    if (body.contains("\"recipient\":\"0009:NOBODY\"")) {
      return new JsonStub.Answer(
          422, "{\"error\":{\"message\":\"destinataire absent de l'annuaire\"}}");
    }
    return new JsonStub.Answer(
        201, "{\"id\":\"F-1\",\"status\":{\"code\":200,\"label\":\"Déposée\"}}");
  }

  private static Outbound to(String receiver) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        "Invoice",
        "INV/2026/000009",
        null,
        receiver,
        "<Invoice>9</Invoice>",
        null,
        "FR32123456789",
        "123456789",
        null);
  }

  @Test
  void depositedIsOnItsWayAndTheDepositCarriesTheBusinessAndTheBuyer() {
    Dispatch d = transport.send(to("0009:55208131700013"));
    assertEquals("F-1", d.providerRef());
    assertEquals(EInvoiceTransports.STATUS_PENDING, d.outcome().state());
    assertTrue(d.outcome().detail().startsWith("deposited"));
    String posted = pdp.calls().get(pdp.calls().size() - 1).body();
    assertTrue(posted.contains("\"supplierSiren\":\"123456789\""));
    assertTrue(posted.contains("\"supplierVatId\":\"FR32123456789\""));
    assertTrue(posted.contains("\"recipient\":\"0009:55208131700013\""));
    assertTrue(posted.contains("\"format\":\"UBL\""));
  }

  @Test
  void theLifecycleReadsAsDeliveredRefusedOrRejected() {
    Outbound d = to("0009:55208131700013");
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, transport.status(d, "F-1").state());
    Outcome refused = transport.status(d, "F-2");
    assertEquals(EInvoiceTransports.STATUS_REJECTED, refused.state());
    assertEquals("refused by the buyer: bon de commande inconnu", refused.detail());
    Outcome rejected = transport.status(d, "F-3");
    assertEquals(EInvoiceTransports.STATUS_REJECTED, rejected.state());
    assertEquals("rejected by the platform: SIRET du destinataire inconnu", rejected.detail());
    assertEquals(EInvoiceTransports.STATUS_PENDING, transport.status(d, "F-4").state());
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, transport.status(d, "F-5").state());
    assertEquals(EInvoiceTransports.STATUS_REJECTED, transport.status(d, "F-GONE").state());
    assertThrows(TransportException.class, () -> transport.status(d, "F-DOWN"));
  }

  @Test
  void aCheckAsksThePlatformAboutTheTaxpayerAndNeverDepositsAnything() {
    int deposits = pdp.calls().size();
    assertEquals(EInvoiceTransport.Readiness.READY, transport.check(taxpayer("123456789")).state());
    assertEquals(
        EInvoiceTransport.Readiness.REFUSED, transport.check(taxpayer("999999999")).state());
    assertEquals(
        EInvoiceTransport.Readiness.UNREACHABLE, transport.check(taxpayer("888888888")).state());
    assertEquals(
        EInvoiceTransport.Readiness.REFUSED,
        FrPdpTransport.forTest("", "").check(taxpayer("123456789")).state());
    // Three questions asked, and not one document deposited: the reform's limbs both count a
    // deposit, so a check that deposited would be a filing nobody made.
    for (JsonStub.Call call : pdp.calls().subList(deposits, pdp.calls().size())) {
      assertEquals("GET", call.method());
    }
  }

  /** What the service hands a check: who the business is at the platform, and nothing to send. */
  private static Outbound taxpayer(String siren) {
    return Checks.credentials(null, "FR32123456789", siren, null);
  }

  @Test
  void aRefusedDepositIsFinalAndADownPlatformIsTriedAgain() {
    Dispatch nobody = transport.send(to("0009:NOBODY"));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, nobody.outcome().state());
    assertEquals("destinataire absent de l'annuaire", nobody.outcome().detail());
    assertThrows(TransportException.class, () -> transport.send(to("0009:DOWN")));
    assertFalse(FrPdpTransport.forTest("", "").isConfigured());
    assertEquals("PDP", transport.name());
  }
}
