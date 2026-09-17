package com.shelfj.iam.mfa;

import static com.shelfj.iam.mfa.SoftwareAuthenticator.UP;
import static com.shelfj.iam.mfa.SoftwareAuthenticator.UV;

import java.util.Base64;

/**
 * The software passkey as an HTTP client sees it: the request bodies a browser would send after
 * {@code navigator.credentials.create} and {@code .get}, every binary field base64url.
 */
public final class MfaTestAuthenticator {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private final SoftwareAuthenticator key;
  private SoftwareAuthenticator.Assertion last;

  public MfaTestAuthenticator(String rpId) {
    key = new SoftwareAuthenticator(rpId);
  }

  public String credentialIdText() {
    return URL.encodeToString(key.credentialId);
  }

  /** The body of {@code POST /auth/mfa/passkeys}. */
  public String registrationJson(
      String registrationToken, String name, byte[] challenge, String origin) {
    var att = key.register(challenge, origin, UP | UV);
    return "{\"registrationToken\":\""
        + registrationToken
        + "\",\"name\":\""
        + name
        + "\",\"clientDataJson\":\""
        + URL.encodeToString(att.clientDataJson())
        + "\",\"attestationObject\":\""
        + URL.encodeToString(att.attestationObject())
        + "\"}";
  }

  /** The body of {@code POST /auth/mfa/login} with a fresh assertion over this challenge. */
  public String assertionJson(String mfaToken, byte[] challenge, String origin) {
    return assertionJsonAs(credentialIdText(), mfaToken, challenge, origin);
  }

  /** The same, claiming to be another credential: what a stranger's authenticator would send. */
  public String assertionJsonAs(
      String credentialId, String mfaToken, byte[] challenge, String origin) {
    last = key.sign(challenge, origin, UP | UV);
    return body(credentialId, mfaToken, last);
  }

  /** The last assertion again, byte for byte: a replay. */
  public String lastAssertionJson(String mfaToken) {
    return body(credentialIdText(), mfaToken, last);
  }

  private static String body(
      String credentialId, String mfaToken, SoftwareAuthenticator.Assertion a) {
    return "{\"mfaToken\":\""
        + mfaToken
        + "\",\"method\":\"PASSKEY\",\"assertion\":{\"credentialId\":\""
        + credentialId
        + "\",\"clientDataJson\":\""
        + URL.encodeToString(a.clientDataJson())
        + "\",\"authenticatorData\":\""
        + URL.encodeToString(a.authenticatorData())
        + "\",\"signature\":\""
        + URL.encodeToString(a.signature())
        + "\"}}";
  }
}
