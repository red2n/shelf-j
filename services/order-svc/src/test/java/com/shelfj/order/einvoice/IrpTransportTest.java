package com.shelfj.order.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.EInvoiceTransports;
import com.shelfj.order.einvoice.EInvoiceTransport.Dispatch;
import com.shelfj.order.einvoice.EInvoiceTransport.Outbound;
import com.shelfj.order.einvoice.EInvoiceTransport.TransportException;
import com.shelfj.order.support.IrpPortalStub;
import com.shelfj.test.JsonStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The portal's sign-in and registration, against a stub portal that does the portal's own
 * cryptography: the password and AppKey opened with its private key, the session key sealed under
 * the AppKey, the INV-01 opened with the session key, the answer sealed with it.
 */
class IrpTransportTest {

  private static final String INV01 =
      "{\"Version\":\"1.1\",\"TranDtls\":{\"TaxSch\":\"GST\",\"SupTyp\":\"B2B\"},"
          + "\"DocDtls\":{\"Typ\":\"INV\",\"No\":\"INV/2026/000001\",\"Dt\":\"16/09/2026\"}}";

  private static JsonStub stub;
  private static IrpPortalStub portal;
  private static IrpTransport transport;

  @BeforeAll
  static void start() {
    stub = JsonStub.start();
    portal = IrpPortalStub.on(stub, "user1", "pass1");
    transport =
        IrpTransport.forTest(
            stub.baseUrl(),
            IrpPortalStub.AUTH_PATH,
            IrpPortalStub.INVOICE_PATH,
            "cid",
            "csec",
            portal.publicKeyBase64());
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  private static Outbound document(String user, String password, String irpJson) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        "Invoice",
        "INV/2026/000001",
        null,
        null,
        "<Invoice/>",
        irpJson,
        "27AAPFU0939F1ZV",
        user,
        password);
  }

  @Test
  void signsInOnceThenRegistersWithTheSessionKeyAndKeepsTheIrn() {
    portal.mode("register");
    Dispatch first = transport.send(document("user1", "pass1", INV01));
    int signedIn = portal.signIns();
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, first.outcome().state());
    assertEquals(64, first.providerRef().length());
    assertTrue(first.outcome().detail().startsWith("registered: IRN " + first.providerRef()));
    assertTrue(first.outcome().response().contains("\"SignedQRCode\""));
    assertTrue(portal.lastInvoice().contains("\"Version\":\"1.1\""));
    Dispatch second = transport.send(document("user1", "pass1", INV01));
    assertEquals(first.providerRef(), second.providerRef());
    assertEquals(signedIn, portal.signIns(), "one session for both");
  }

  @Test
  void anInvoiceAlreadyRegisteredIsRegisteredWithThePortalsIrn() {
    portal.mode("duplicate");
    try {
      Dispatch d = transport.send(document("user1", "pass1", INV01));
      assertEquals(EInvoiceTransports.STATUS_ACCEPTED, d.outcome().state());
      assertEquals(64, d.providerRef().length());
      assertTrue(d.outcome().detail().startsWith("already registered"));
    } finally {
      portal.mode("register");
    }
  }

  @Test
  void thePortalsRefusalIsFinalAndItsOutageIsTriedAgain() {
    portal.mode("refuse");
    try {
      Dispatch d = transport.send(document("user1", "pass1", INV01));
      assertEquals(EInvoiceTransports.STATUS_REJECTED, d.outcome().state());
      assertTrue(d.outcome().detail().contains("2172 Recipient GSTIN cannot be blank"));
      assertNull(d.providerRef());
    } finally {
      portal.mode("register");
    }
    portal.mode("down");
    try {
      assertThrows(
          TransportException.class, () -> transport.send(document("user1", "pass1", INV01)));
    } finally {
      portal.mode("register");
    }
  }

  @Test
  void wrongCredentialsAndMissingPiecesAreRefusedWithoutARetry() {
    Dispatch wrong = transport.send(document("user1", "nope", INV01));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, wrong.outcome().state());
    assertTrue(wrong.outcome().detail().contains("refused the business's credentials"));
    Dispatch noJson = transport.send(document("user1", "pass1", null));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, noJson.outcome().state());
    Dispatch noSecret = transport.send(document("user1", null, INV01));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, noSecret.outcome().state());
    assertTrue(transport.isConfigured());
    assertTrue(transport.needsSecret());
    assertFalse(
        IrpTransport.forTest("", "/a", "/i", "", "", portal.publicKeyBase64()).isConfigured());
    assertEquals("NIC", transport.name());
  }
}
