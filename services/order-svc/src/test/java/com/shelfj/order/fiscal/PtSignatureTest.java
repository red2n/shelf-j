package com.shelfj.order.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The Portuguese signature is a fixed string signed with a fixed algorithm; the AT's validator
 * recomputes it from the SAF-T file, so every byte of the string matters.
 */
class PtSignatureTest {

  static KeyPair rsa() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(1024);
    return g.generateKeyPair();
  }

  @Test
  void theCanonicalStringIsDateEntryTimeNumberGrossAndPreviousHash() {
    String c =
        PtSignature.canonical(
            LocalDate.of(2026, 9, 12),
            Instant.parse("2026-09-12T10:04:31.123456Z"),
            "FS MAIN2026/42",
            new BigDecimal("12.5"),
            "PREV==");
    assertEquals("2026-09-12;2026-09-12T10:04:31;FS MAIN2026/42;12.50;PREV==", c);
  }

  @Test
  void theFirstDocumentInASeriesChainsOnAnEmptyString() {
    String c =
        PtSignature.canonical(
            LocalDate.of(2026, 1, 1),
            Instant.parse("2026-01-01T00:00:00Z"),
            "FS A/1",
            BigDecimal.ONE,
            null);
    assertTrue(c.endsWith(";1.00;"));
  }

  @Test
  void theInvoiceNumberIsTypeSeriesSlashCounter() {
    assertEquals("FS PT-LIS-2026/42", PtSignature.invoiceNo("PT-LIS-2026-000042", 42));
    assertEquals("FS 2026/7", PtSignature.invoiceNo("2026-000007", 7));
  }

  @Test
  void aSignatureVerifiesWithThePublicHalfAndNotAfterAChangedFigure() throws Exception {
    KeyPair pair = rsa();
    String canonical = "2026-09-12;2026-09-12T10:04:31;FS A/1;12.50;";
    String sig = PtSignature.sign(pair.getPrivate(), canonical);
    assertTrue(PtSignature.verify(pair.getPublic(), canonical, sig));
    assertFalse(PtSignature.verify(pair.getPublic(), canonical.replace("12.50", "12.51"), sig));
    assertFalse(PtSignature.verify(pair.getPublic(), canonical, "not base64!"));
    // The public half derived from the private key is the same key.
    assertEquals(
        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
        Base64.getEncoder()
            .encodeToString(PtSignature.publicKeyOf(pair.getPrivate()).getEncoded()));
  }

  @Test
  void theReceiptPrintsCharactersOneElevenTwentyOneAndThirtyOne() {
    String hash = "A123456789B123456789C123456789D123456789";
    assertEquals("ABCD", PtSignature.excerpt(hash));
    assertNull(PtSignature.excerpt("short"));
    assertNull(PtSignature.excerpt(null));
  }

  @Test
  void aPemKeyLoadsAndAnythingElseIsRefusedByName() throws Exception {
    KeyPair pair = rsa();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    assertEquals(pair.getPrivate(), PtSignature.loadPrivateKey(pem));
    var e =
        assertThrows(IllegalArgumentException.class, () -> PtSignature.loadPrivateKey("garbage"));
    assertTrue(e.getMessage().contains("shelfj.fiscal.pt.private-key"));
  }

  @Test
  void aNifCarriesAModElevenCheckDigit() {
    assertTrue(PtSignature.isValidNif("500000000"));
    assertTrue(PtSignature.isValidNif("123456789"));
    assertFalse(PtSignature.isValidNif("123456780"));
    assertFalse(PtSignature.isValidNif("12345678"));
    assertFalse(PtSignature.isValidNif("PT123456789"));
    assertFalse(PtSignature.isValidNif(null));
  }

  @Test
  void aStoreCannotBePlacedUnderPtSaftWithoutAKeyOnTheServer() {
    SaftPtModule module = new SaftPtModule();
    module.key = new PtSigningKey();
    var settings =
        new com.shelfj.order.domain.Domain.FiscalStoreSettings(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            "PT_SAFT",
            "500000000",
            null,
            null,
            null,
            null);
    ApiException e = assertThrows(ApiException.class, () -> module.validate(settings, null));
    assertEquals("FISCAL_PT_KEY_NOT_CONFIGURED", e.code());
    assertEquals(409, e.status());
  }
}
