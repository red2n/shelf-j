package com.storeql.iam.sso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proof Key for Code Exchange (RFC 7636), S256 only. Used twice: this service proves to the
 * provider that it is the one that started the sign-in, and the app proves to this service that it
 * is the one the ticket was made for — so a ticket or a code lifted from a URL, or planted in
 * somebody else's browser, is worth nothing.
 */
public final class Pkce {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private Pkce() {}

  /** 256 random bits, base64url: 43 characters, inside RFC 7636's 43 to 128. */
  public static String newVerifier() {
    return random(32);
  }

  /** Random bits for a state or a nonce. */
  public static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return URL.encodeToString(b);
  }

  /** {@code BASE64URL(SHA256(verifier))}. */
  public static String challenge(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return URL.encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always present", e);
    }
  }

  /** Whether the verifier is RFC 7636's shape: 43 to 128 unreserved characters. */
  public static boolean wellFormed(String verifier) {
    return verifier != null
        && verifier.length() >= 43
        && verifier.length() <= 128
        && verifier.chars().allMatch(Pkce::unreserved);
  }

  /** Whether the verifier is the one the challenge was made from, compared in constant time. */
  public static boolean matches(String verifier, String challenge) {
    if (!wellFormed(verifier) || challenge == null) return false;
    return MessageDigest.isEqual(
        challenge(verifier).getBytes(StandardCharsets.US_ASCII),
        challenge.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean unreserved(int c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }
}
