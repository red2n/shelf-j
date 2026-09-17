package com.shelfj.iam.mfa;

import static com.shelfj.iam.mfa.SoftwareAuthenticator.UP;
import static com.shelfj.iam.mfa.SoftwareAuthenticator.UV;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The verifier against a software authenticator: what must pass, and each way it must not. */
class WebAuthnTest {

  private static final String ORIGIN = "https://admin.shop.example";
  private static final WebAuthn.RelyingParty RP =
      new WebAuthn.RelyingParty("shop.example", Set.of(ORIGIN, "https://pos.shop.example"));

  private static byte[] challenge() {
    byte[] c = new byte[32];
    new SecureRandom().nextBytes(c);
    return c;
  }

  private final SoftwareAuthenticator key = new SoftwareAuthenticator("shop.example");

  private WebAuthn.Registered registered() {
    byte[] challenge = challenge();
    var att = key.register(challenge, ORIGIN, UP | UV);
    return WebAuthn.registration(
        att.clientDataJson(), att.attestationObject(), challenge, RP, true);
  }

  private void refusedAssertion(
      SoftwareAuthenticator.Assertion a,
      byte[] challenge,
      WebAuthn.Registered reg,
      long stored,
      boolean uv,
      String why) {
    WebAuthn.Refused refused =
        assertThrows(
            WebAuthn.Refused.class,
            () ->
                WebAuthn.assertion(
                    a.clientDataJson(),
                    a.authenticatorData(),
                    a.signature(),
                    challenge,
                    RP,
                    reg.publicKeyCose(),
                    stored,
                    uv),
            why);
    assertFalse(refused.getMessage().isBlank());
  }

  /** Judges a sign-in against the registered key, user verification required. */
  private static long accepted(
      SoftwareAuthenticator.Assertion a, byte[] challenge, WebAuthn.Registered reg, long stored) {
    return WebAuthn.assertion(
        a.clientDataJson(),
        a.authenticatorData(),
        a.signature(),
        challenge,
        RP,
        reg.publicKeyCose(),
        stored,
        true);
  }

  // ── what must pass ─────────────────────────────────────────────────────────

  @Test
  void aPasskeyIsRegisteredAndThenSignsIn() {
    WebAuthn.Registered reg = registered();
    assertArrayEquals(key.credentialId, reg.credentialId());
    assertArrayEquals(key.coseKey(), reg.publicKeyCose());
    assertEquals(0, reg.signCount());
    assertTrue(reg.userVerified());

    long stored = reg.signCount();
    for (int i = 1; i <= 3; i++) {
      byte[] challenge = challenge();
      var a = key.sign(challenge, "https://pos.shop.example", UP | UV);
      stored = accepted(a, challenge, reg, stored);
      assertEquals(i, stored, "the counter is kept as the authenticator counts");
    }
  }

  @Test
  void anAuthenticatorThatKeepsNoCounterIsNotMistakenForAClone() {
    WebAuthn.Registered reg = registered();
    byte[] challenge = challenge();
    key.counter = -1; // sign() counts up to 0: many platform passkeys always say 0
    var a = key.sign(challenge, ORIGIN, UP | UV);
    assertEquals(0, accepted(a, challenge, reg, 0));
  }

  // ── registration refused ───────────────────────────────────────────────────

  @Test
  void aRegistrationForAnotherChallengeOriginCeremonyOrSiteIsRefused() {
    byte[] challenge = challenge();
    var good = key.register(challenge, ORIGIN, UP | UV);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                good.clientDataJson(), good.attestationObject(), challenge(), RP, true),
        "another challenge");

    var phished = key.register(challenge, "https://admin.shop-example.com", UP | UV);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                phished.clientDataJson(), phished.attestationObject(), challenge, RP, true),
        "a look-alike origin");

    var asSignIn = SoftwareAuthenticator.clientData("webauthn.get", challenge, ORIGIN);
    assertThrows(
        WebAuthn.Refused.class,
        () -> WebAuthn.registration(asSignIn, good.attestationObject(), challenge, RP, true),
        "a sign-in passed off as a registration");

    var elsewhere = new SoftwareAuthenticator("evil.example").register(challenge, ORIGIN, UP | UV);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                elsewhere.clientDataJson(), elsewhere.attestationObject(), challenge, RP, true),
        "made for another relying party");

    var unverified = key.register(challenge, ORIGIN, UP);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                unverified.clientDataJson(), unverified.attestationObject(), challenge, RP, true),
        "present but not verified");
    assertFalse(
        WebAuthn.registration(
                unverified.clientDataJson(), unverified.attestationObject(), challenge, RP, false)
            .userVerified());

    var absent = key.register(challenge, ORIGIN, 0);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                absent.clientDataJson(), absent.attestationObject(), challenge, RP, false),
        "nobody present");

    byte[] embedded =
        new String(good.clientDataJson(), StandardCharsets.UTF_8)
            .replace("\"crossOrigin\":false", "\"crossOrigin\":true")
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        WebAuthn.Refused.class,
        () -> WebAuthn.registration(embedded, good.attestationObject(), challenge, RP, true),
        "run inside another site's frame");
  }

  @Test
  void aKeyThisServiceDoesNotAcceptIsRefusedAtTheDoor() {
    // A point that is not on P-256: the opening move of an invalid-curve attack.
    byte[] x = SoftwareAuthenticator.fixed(BigInteger.valueOf(5));
    byte[] y = SoftwareAuthenticator.fixed(BigInteger.valueOf(7));
    assertThrows(
        WebAuthn.Refused.class, () -> WebAuthn.publicKey(SoftwareAuthenticator.coseEc2(x, y)));
    // Short coordinates, another curve (P-384 = 2), another algorithm (ES512 = -36), not a map.
    assertThrows(
        WebAuthn.Refused.class,
        () -> WebAuthn.publicKey(SoftwareAuthenticator.coseEc2(new byte[31], new byte[32])));
    byte[] good = key.coseKey();
    byte[] otherCurve = good.clone();
    otherCurve[6] = 0x02;
    assertThrows(WebAuthn.Refused.class, () -> WebAuthn.publicKey(otherCurve));
    byte[] otherAlg = good.clone();
    otherAlg[4] = 0x38;
    assertThrows(WebAuthn.Refused.class, () -> WebAuthn.publicKey(otherAlg));
    assertThrows(WebAuthn.Refused.class, () -> WebAuthn.publicKey(new byte[] {0x01}));
    assertThrows(WebAuthn.Refused.class, () -> WebAuthn.publicKey(new byte[] {(byte) 0xff, 0x00}));
  }

  @Test
  void rubbishInPlaceOfAnAttestationIsRefusedNotCrashedOn() {
    byte[] challenge = challenge();
    byte[] clientData = SoftwareAuthenticator.clientData("webauthn.create", challenge, ORIGIN);
    for (byte[] bad :
        new byte[][] {
          {}, {0x00}, {(byte) 0xa0}, new byte[20_000], "not cbor".getBytes(StandardCharsets.UTF_8)
        }) {
      assertThrows(
          WebAuthn.Refused.class,
          () -> WebAuthn.registration(clientData, bad, challenge, RP, true));
    }
    var att = key.register(challenge, ORIGIN, UP | UV);
    assertThrows(
        WebAuthn.Refused.class,
        () ->
            WebAuthn.registration(
                "{".getBytes(StandardCharsets.UTF_8),
                att.attestationObject(),
                challenge,
                RP,
                true));
    assertThrows(
        WebAuthn.Refused.class,
        () -> WebAuthn.registration(clientData, att.attestationObject(), new byte[4], RP, true),
        "a challenge too short to be one of ours");
    // Cut short anywhere, it is refused, never an exception of another kind.
    byte[] whole = att.attestationObject();
    for (int cut = 1; cut < whole.length; cut += 7) {
      byte[] part = java.util.Arrays.copyOf(whole, cut);
      assertThrows(
          WebAuthn.Refused.class,
          () -> WebAuthn.registration(att.clientDataJson(), part, challenge, RP, true));
    }
  }

  // ── sign-in refused ────────────────────────────────────────────────────────

  @Test
  void aSignInThatIsNotThisKeysThisChallengesOrThisSitesIsRefused() {
    WebAuthn.Registered reg = registered();
    byte[] challenge = challenge();

    var replayed = key.sign(challenge, ORIGIN, UP | UV);
    refusedAssertion(
        replayed, challenge(), reg, 0, true, "an assertion made for an earlier challenge");

    var phished = key.sign(challenge, "https://admin.shop.example.evil.test", UP | UV);
    refusedAssertion(
        phished, challenge, reg, 0, true, "signed for a look-alike origin: the point of a passkey");

    var stranger = new SoftwareAuthenticator("shop.example").sign(challenge, ORIGIN, UP | UV);
    refusedAssertion(stranger, challenge, reg, 0, true, "another authenticator's signature");

    var elsewhere = new SoftwareAuthenticator("evil.example");
    refusedAssertion(
        elsewhere.sign(challenge, ORIGIN, UP | UV),
        challenge,
        reg,
        0,
        true,
        "made for another relying party");

    refusedAssertion(
        key.sign(challenge, ORIGIN, UP), challenge, reg, 0, true, "present but not verified");
    refusedAssertion(
        key.sign(challenge, ORIGIN, UV),
        challenge,
        reg,
        0,
        false,
        "verified, it says, but nobody present");

    var good = key.sign(challenge, ORIGIN, UP | UV);
    byte[] flipped = good.signature().clone();
    flipped[flipped.length - 1] ^= 0x01;
    refusedAssertion(
        new SoftwareAuthenticator.Assertion(
            good.clientDataJson(), good.authenticatorData(), flipped),
        challenge,
        reg,
        0,
        true,
        "one bit of the signature");

    byte[] raised = good.authenticatorData().clone();
    raised[36] = (byte) 0x7f; // the counter pushed up after signing
    refusedAssertion(
        new SoftwareAuthenticator.Assertion(good.clientDataJson(), raised, good.signature()),
        challenge,
        reg,
        0,
        true,
        "authenticator data changed after signing");

    byte[] asCreate = SoftwareAuthenticator.clientData("webauthn.create", challenge, ORIGIN);
    refusedAssertion(
        new SoftwareAuthenticator.Assertion(
            asCreate, good.authenticatorData(), key.signature(good.authenticatorData(), asCreate)),
        challenge,
        reg,
        0,
        true,
        "a registration passed off as a sign-in");
  }

  @Test
  void aCounterThatGoesBackwardsIsACloneAndIsRefused() {
    WebAuthn.Registered reg = registered();
    byte[] challenge = challenge();
    var a = key.sign(challenge, ORIGIN, UP | UV); // counter 1
    refusedAssertion(a, challenge, reg, 5, true, "the kept counter is already past it");
    refusedAssertion(a, challenge, reg, 1, true, "or level with it");
    assertEquals(1, accepted(a, challenge, reg, 0));
  }
}
