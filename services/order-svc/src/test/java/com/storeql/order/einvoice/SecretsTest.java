package com.storeql.order.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** A credential sealed for the database opens as it was, and only under the same key. */
class SecretsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void sealedTwiceLooksDifferentAndOpensTheSame() {
    Secrets s = Secrets.forTest(KEY);
    assertTrue(s.isConfigured());
    String a = s.seal("hunter2");
    String b = s.seal("hunter2");
    assertNotEquals(a, b);
    assertTrue(a.startsWith("v1:"));
    assertEquals("hunter2", s.open(a));
    assertEquals("hunter2", s.open(b));
    assertNull(s.open(null));
  }

  @Test
  void aByteChangedOrAnotherKeyIsRefused() {
    Secrets s = Secrets.forTest(KEY);
    String sealed = s.seal("hunter2");
    String tampered = sealed.substring(0, sealed.length() - 3) + "AAA";
    assertThrows(IllegalStateException.class, () -> s.open(tampered));
    byte[] other = new byte[32];
    other[0] = 1;
    Secrets another = Secrets.forTest(Base64.getEncoder().encodeToString(other));
    assertThrows(IllegalStateException.class, () -> another.open(sealed));
    assertThrows(IllegalStateException.class, () -> s.open("plain text"));
  }

  @Test
  void withoutAKeyNothingIsSealedOrOpened() {
    Secrets none = Secrets.forTest(null);
    assertFalse(none.isConfigured());
    assertThrows(IllegalStateException.class, () -> none.seal("x"));
    assertThrows(IllegalStateException.class, () -> none.open("v1:AAAA"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Secrets.forTest(Base64.getEncoder().encodeToString(new byte[5])));
  }
}
