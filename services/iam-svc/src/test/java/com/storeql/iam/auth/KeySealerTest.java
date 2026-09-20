package com.storeql.iam.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A signing key's private half at rest (20.15): sealed, never the same twice, refused if touched.
 */
class KeySealerTest {

  private static final String SECRET = "a-sealing-secret-of-at-least-32-characters";
  private static final byte[] KEY = "not really a key, but bytes".getBytes(StandardCharsets.UTF_8);

  @Test
  void whatIsSealedOpensAndNeverLooksTheSameTwice() {
    KeySealer sealer = new KeySealer(SECRET);
    String one = sealer.seal(KEY);
    String two = sealer.seal(KEY);
    assertNotEquals(one, two, "a fresh nonce each time");
    assertArrayEquals(KEY, sealer.open(one));
    assertArrayEquals(KEY, sealer.open(two));
  }

  @Test
  void aChangedByteAnotherSecretOrAnUnsealedValueIsRefused() {
    KeySealer sealer = new KeySealer(SECRET);
    String sealed = sealer.seal(KEY);
    char last = sealed.charAt(sealed.length() - 2);
    String tampered =
        sealed.substring(0, sealed.length() - 2)
            + (last == 'A' ? 'B' : 'A')
            + sealed.charAt(sealed.length() - 1);
    assertThrows(IllegalStateException.class, () -> sealer.open(tampered));
    assertThrows(
        IllegalStateException.class,
        () -> new KeySealer("another-sealing-secret-of-at-least-32-chars").open(sealed));
    assertThrows(IllegalStateException.class, () -> sealer.open("plain text"));
    assertThrows(IllegalStateException.class, () -> sealer.open(null));
  }

  @Test
  void aShortSecretIsNoSecret() {
    assertThrows(IllegalStateException.class, () -> new KeySealer("too short"));
    assertThrows(IllegalStateException.class, () -> new KeySealer(null));
  }
}
