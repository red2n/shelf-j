package com.shelfj.test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * An RSA signing key for tests: the key pair, its JWKS document, and RS256 tokens signed with it —
 * what iam-svc publishes and issues (20.15), without iam-svc. Tokens are built by hand so this
 * module needs no JWT library.
 */
public final class SigningKeysFixture {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private final String kid;
  private final RSAPublicKey publicKey;
  private final RSAPrivateKey privateKey;

  private SigningKeysFixture(String kid, KeyPair pair) {
    this.kid = kid;
    this.publicKey = (RSAPublicKey) pair.getPublic();
    this.privateKey = (RSAPrivateKey) pair.getPrivate();
  }

  /** A fresh 2048-bit key with the given key id. */
  public static SigningKeysFixture generate(String kid) {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return new SigningKeysFixture(kid, gen.generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String kid() {
    return kid;
  }

  public RSAPublicKey publicKey() {
    return publicKey;
  }

  public RSAPrivateKey privateKey() {
    return privateKey;
  }

  /** This key as one JWK (RFC 7517). */
  public String jwk() {
    return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
        + kid
        + "\",\"n\":\""
        + URL.encodeToString(unsigned(publicKey.getModulus()))
        + "\",\"e\":\""
        + URL.encodeToString(unsigned(publicKey.getPublicExponent()))
        + "\"}";
  }

  /** A key set holding this key alone. */
  public String jwksJson() {
    return "{\"keys\":[" + jwk() + "]}";
  }

  /**
   * An RS256 token carrying this key's id.
   *
   * @param claims string, number, boolean or list-of-string values
   */
  public String sign(String issuer, String subject, Map<String, Object> claims, long ttlSeconds) {
    return sign("RS256", issuer, subject, claims, ttlSeconds);
  }

  /** The same token with the header's {@code alg} as given, for tests of what must be refused. */
  public String sign(
      String headerAlg, String issuer, String subject, Map<String, Object> claims, long ttl) {
    long now = Instant.now().getEpochSecond();
    StringBuilder payload =
        new StringBuilder("{\"iss\":\"" + issuer + "\",\"sub\":\"" + subject + "\"");
    payload.append(",\"iat\":").append(now).append(",\"exp\":").append(now + ttl);
    for (Map.Entry<String, Object> c : claims.entrySet()) {
      payload.append(",\"").append(c.getKey()).append("\":").append(json(c.getValue()));
    }
    payload.append('}');
    String header = "{\"alg\":\"" + headerAlg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
    String signingInput =
        URL.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + URL.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(privateKey);
      s.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signingInput + "." + URL.encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String json(Object v) {
    if (v instanceof List<?> list) {
      StringBuilder b = new StringBuilder("[");
      for (Object o : list) {
        if (b.length() > 1) b.append(',');
        b.append(json(o));
      }
      return b.append(']').toString();
    }
    if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
    return "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static byte[] unsigned(BigInteger i) {
    byte[] b = i.toByteArray();
    if (b.length > 1 && b[0] == 0) {
      byte[] t = new byte[b.length - 1];
      System.arraycopy(b, 1, t, 0, t.length);
      return t;
    }
    return b;
  }
}
