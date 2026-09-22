package com.storeql.iam.sso;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * An ID token checked the way OpenID Connect Core §3.1.3.7 says a client must, before anything in
 * it is believed: RS256 and nothing else, signed by a key the provider publishes, issued by the
 * provider this business connected, for this business's client, not expired, and carrying the nonce
 * this sign-in was sent with — which is what stops a token minted for another sign-in being
 * replayed into this one.
 *
 * @param subject the provider's {@code sub}: who this person is at the provider, for good
 * @param email the address the provider asserts, if it asserts one
 * @param emailVerified whether it says it verified that address — false when it says not, and when
 *     it does not say
 * @param amr the authentication methods the provider reports (RFC 8176), empty when it reports none
 */
public record IdToken(String subject, String email, boolean emailVerified, List<String> amr) {

  /** How far apart this clock and a provider's may be. */
  static final long SKEW_SECONDS = 60;

  /** RFC 7519's own limit on {@code sub}; anything longer is not an identifier. */
  private static final int MAX_SUBJECT = 255;

  public IdToken {
    amr = List.copyOf(amr);
  }

  /**
   * Whether the provider says a second factor was used: RFC 8176's {@code mfa}. A provider that
   * reports nothing has proved nothing, and the platform's own second factor is asked for instead
   * where the business requires one.
   */
  public boolean multiFactor() {
    return amr.contains("mfa");
  }

  /**
   * Checks an ID token.
   *
   * @param keys the provider's key for a key id, or empty
   * @throws SsoRefused {@link SsoRefused#ID_TOKEN_INVALID} for any failed check
   */
  public static IdToken verify(
      String token,
      Function<String, Optional<RSAPublicKey>> keys,
      String issuer,
      String clientId,
      String nonce,
      Clock clock) {
    DecodedJWT decoded;
    try {
      decoded = JWT.decode(token);
    } catch (JWTVerificationException e) {
      throw new SsoRefused(SsoRefused.ID_TOKEN_INVALID, "ID token refused: not a JWT", e);
    }
    // Pinned before any key is looked at (RFC 8725 §3.1): never "none", never an HMAC keyed with
    // the provider's public key.
    if (!"RS256".equals(decoded.getAlgorithm())) {
      throw invalid("signed with " + decoded.getAlgorithm() + ", not RS256");
    }
    RSAPublicKey key =
        keys.apply(decoded.getKeyId())
            .orElseThrow(() -> invalid("signed by a key the provider does not publish"));
    DecodedJWT verified;
    try {
      JWTVerifier verifier =
          ((JWTVerifier.BaseVerification)
                  JWT.require(Algorithm.RSA256(key, null))
                      .withIssuer(issuer)
                      .withAudience(clientId)
                      .withClaimPresence("exp")
                      .withClaimPresence("iat")
                      .acceptIssuedAt(SKEW_SECONDS)
                      .acceptNotBefore(SKEW_SECONDS)
                      .acceptExpiresAt(SKEW_SECONDS))
              .build(clock);
      verified = verifier.verify(token);
    } catch (JWTVerificationException e) {
      throw new SsoRefused(SsoRefused.ID_TOKEN_INVALID, "ID token refused: " + e.getMessage(), e);
    }
    List<String> audience = verified.getAudience();
    String azp = verified.getClaim("azp").asString();
    // §3.1.3.7 items 4 and 5: with more than one audience the token must say which party it was
    // issued to, and whenever it says so, that party is this client.
    if ((audience.size() > 1 && azp == null) || (azp != null && !azp.equals(clientId))) {
      throw invalid("issued to another party");
    }
    String got = verified.getClaim("nonce").asString();
    if (got == null
        || !MessageDigest.isEqual(
            got.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) {
      throw invalid("the nonce is not this sign-in's");
    }
    String subject = verified.getSubject();
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT) {
      throw invalid("no subject");
    }
    List<String> amr = verified.getClaim("amr").asList(String.class);
    return new IdToken(
        subject,
        email(verified.getClaim("email")),
        bool(verified.getClaim("email_verified")),
        amr == null ? List.of() : amr);
  }

  static String email(Claim claim) {
    String email = claim.isMissing() || claim.isNull() ? null : claim.asString();
    return email == null || email.isBlank() ? null : email.trim();
  }

  /** True, or the string of it: some providers send {@code "true"}. Anything else is not. */
  static boolean bool(Claim claim) {
    if (claim.isMissing() || claim.isNull()) return false;
    Boolean b = claim.asBoolean();
    if (b != null) return b;
    String s = claim.asString();
    return s != null && "true".equalsIgnoreCase(s.trim());
  }

  /** The same person, with what the userinfo endpoint said filling what the token did not. */
  public IdToken withContact(String email, boolean emailVerified) {
    return new IdToken(subject, email, emailVerified, amr);
  }

  private static SsoRefused invalid(String why) {
    return new SsoRefused(SsoRefused.ID_TOKEN_INVALID, "ID token refused: " + why);
  }
}
