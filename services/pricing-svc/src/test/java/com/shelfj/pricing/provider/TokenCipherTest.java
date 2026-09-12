package com.shelfj.pricing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** A token at rest is sealed; a tampered one is refused; no key means no tokens. */
class TokenCipherTest {

  static TokenCipher withKey() {
    TokenCipher c = new TokenCipher();
    c.use(Base64.getEncoder().encodeToString(new byte[32]));
    return c;
  }

  @Test
  void roundTripsAndNeverProducesTheSameCiphertextTwice() {
    TokenCipher c = withKey();
    String a = c.encrypt("access-token-1");
    String b = c.encrypt("access-token-1");
    assertNotEquals(a, b);
    assertEquals("access-token-1", c.decrypt(a));
    assertEquals("access-token-1", c.decrypt(b));
    assertFalse(a.contains("access"));
  }

  @Test
  void aTamperedValueIsRefused() {
    TokenCipher c = withKey();
    String sealed = c.encrypt("secret");
    byte[] raw = Base64.getDecoder().decode(sealed);
    raw[raw.length - 1] ^= 0x01;
    assertThrows(
        IllegalStateException.class, () -> c.decrypt(Base64.getEncoder().encodeToString(raw)));
  }

  @Test
  void withoutAKeyNothingIsHeld() {
    TokenCipher c = new TokenCipher();
    assertFalse(c.isConfigured());
    assertThrows(IllegalStateException.class, () -> c.encrypt("x"));
    assertTrue(withKey().isConfigured());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenCipher().use(Base64.getEncoder().encodeToString(new byte[7])));
  }
}
