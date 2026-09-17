package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.test.SigningKeysFixture;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SigningKeySetTest {

  @Test
  void aPublishedKeyIsReadBackAsTheSameKey() {
    SigningKeysFixture keys = SigningKeysFixture.generate("key-one");

    var parsed = SigningKeySet.parse(keys.jwksJson());

    assertEquals(1, parsed.size());
    assertEquals(keys.publicKey().getModulus(), parsed.get("key-one").getModulus());
    assertEquals(keys.publicKey().getPublicExponent(), parsed.get("key-one").getPublicExponent());
  }

  @Test
  void keysThatAreNotRs256SigningKeysAreLeftOut() {
    String jwks =
        "{\"keys\":["
            + "{\"kty\":\"oct\",\"kid\":\"shared\",\"k\":\"c2VjcmV0\"},"
            + "{\"kty\":\"RSA\",\"kid\":\"other-alg\",\"alg\":\"PS256\",\"n\":\"AQAB\",\"e\":\"AQAB\"},"
            + "{\"kty\":\"RSA\",\"n\":\"AQAB\",\"e\":\"AQAB\"}"
            + "]}";

    assertTrue(SigningKeySet.parse(jwks).isEmpty());
  }

  @Test
  void aDocumentThatIsNotAKeySetYieldsNoKeysRatherThanAnError() {
    assertTrue(SigningKeySet.parse("<html>502 Bad Gateway</html>").isEmpty());
    assertTrue(
        SigningKeySet.parse("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k\",\"n\":\"%%\"}]}").isEmpty());
    assertTrue(SigningKeySet.parse("{}").isEmpty());
  }

  @Test
  void anUnreachableIssuerLeavesTheSetUnloadedAndAsksAgainOnlyAfterAPause() {
    SigningKeySet set = new SigningKeySet();
    set.iamUrl = Optional.of("http://localhost:1");
    set.webClient = io.helidon.webclient.api.WebClient.builder().build();

    assertTrue(set.key("any").isEmpty());
    assertFalse(set.loaded());
    // A second unknown key straight after must not hammer iam-svc: nothing to assert on the wire
    // here, only that it answers at once and stays unloaded.
    assertTrue(set.key("another").isEmpty());
    assertFalse(set.loaded());
  }

  @Test
  void aBlankKeyIdIsNeverLookedUp() {
    SigningKeysFixture keys = SigningKeysFixture.generate("key-one");
    SigningKeySet set = SigningKeySet.of(SigningKeySet.parse(keys.jwksJson()));

    assertTrue(set.key(null).isEmpty());
    assertTrue(set.key(" ").isEmpty());
    assertTrue(set.key("key-one").isPresent());
  }
}
