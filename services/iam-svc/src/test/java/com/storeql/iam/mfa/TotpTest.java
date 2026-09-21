package com.storeql.iam.mfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TotpTest {

  // RFC 6238 appendix B, SHA-1 rows; the RFC prints eight digits, an authenticator app shows six.
  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @Test
  void theRfcsOwnVectors() {
    assertEquals("287082", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59))));
    assertEquals("081804", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109L))));
    assertEquals("050471", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111111L))));
    assertEquals("005924", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890L))));
    assertEquals("279037", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000L))));
    assertEquals("353130", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(20000000000L))));
  }

  @Test
  void aCodeIsGoodOneStepEitherSideOfNowAndNoFurther() {
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    long step = Totp.stepAt(now);
    assertEquals(step, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, -1));
    assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, -1));
    assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, -1));
    assertEquals(-1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, -1));
    assertEquals(-1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, -1));
  }

  @Test
  void aCodeIsNeverGoodTwice() {
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    long step = Totp.stepAt(now);
    String code = Totp.code(RFC_SECRET, step);
    assertEquals(step, Totp.verify(RFC_SECRET, code, now, -1));
    assertEquals(-1, Totp.verify(RFC_SECRET, code, now, step), "the same code again");
    assertEquals(
        -1,
        Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, step),
        "nor an older code after a newer one was used");
    assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, step));
  }

  @Test
  void whatIsNotSixDigitsIsNotACode() {
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    for (String bad :
        new String[] {null, "", "12345", "1234567", "12345a", "١٢٣٤٥٦", "-12345", "12 34"}) {
      assertEquals(-1, Totp.verify(RFC_SECRET, bad, now, -1), String.valueOf(bad));
    }
    String spaced = Totp.code(RFC_SECRET, Totp.stepAt(now));
    assertEquals(
        Totp.stepAt(now),
        Totp.verify(RFC_SECRET, spaced.substring(0, 3) + " " + spaced.substring(3), now, -1),
        "as an app shows it: 123 456");
  }

  @Test
  void anotherSecretsCodeIsRefusedAndGuessingIsAOneInAMillionShot() {
    Instant now = Instant.ofEpochSecond(1_800_000_000L);
    byte[] other = Totp.newSecret();
    // A right code for another secret matches ours only by the one-in-a-million-per-step chance.
    int accepted = 0;
    for (int i = 0; i < 200; i++) {
      if (Totp.verify(RFC_SECRET, Totp.code(other, Totp.stepAt(now) + i * 7L), now, -1) >= 0)
        accepted++;
    }
    assertTrue(accepted <= 1, "accepted " + accepted + " of 200 foreign codes");
  }

  @Test
  void secretsAreLongRandomAndRoundTripThroughBase32() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      byte[] secret = Totp.newSecret();
      assertEquals(20, secret.length);
      String text = Totp.base32(secret);
      assertEquals(32, text.length());
      assertArrayEquals(secret, Totp.fromBase32(text));
      assertArrayEquals(secret, Totp.fromBase32(text.toLowerCase().replaceAll("(.{4})", "$1 ")));
      assertTrue(seen.add(text));
    }
    assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(RFC_SECRET));
    assertThrows(IllegalArgumentException.class, () -> Totp.fromBase32("not!base32"));
  }

  @Test
  void theUriAnAppReadsNamesTheIssuerTheAccountAndTheParameters() {
    String uri = Totp.otpauthUri("StoreQL", "ana owner@example.com", RFC_SECRET);
    assertTrue(uri.startsWith("otpauth://totp/StoreQL:ana%20owner%40example.com?"), uri);
    for (String part :
        List.of(
            "secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            "issuer=StoreQL",
            "algorithm=SHA1",
            "digits=6",
            "period=30")) {
      assertTrue(uri.contains(part), part);
    }
  }
}
