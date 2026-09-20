package com.storeql.iam.mfa;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (20.12; RFC 6238 over RFC 4226): HMAC-SHA-1, six digits, thirty
 * seconds — what every authenticator app speaks. A code is accepted one step either side of now,
 * for a phone whose clock has drifted, and never twice: the caller keeps the last step that was
 * accepted and a code from that step or before is refused, so a code read over a shoulder is dead
 * the moment its owner has used it.
 */
public final class Totp {

  public static final int DIGITS = 6;
  public static final int PERIOD_SECONDS = 30;
  private static final int WINDOW_STEPS = 1;
  private static final int SECRET_BYTES = 20;
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  /** A new shared secret: 160 bits, as RFC 4226 recommends. */
  public static byte[] newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  /** The code for one time step. */
  public static String code(byte[] secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA-1 is not available", e);
    }
  }

  /** The time step an instant falls in. */
  public static long stepAt(Instant when) {
    return Math.floorDiv(when.getEpochSecond(), PERIOD_SECONDS);
  }

  /**
   * Checks a code against the steps around {@code now}.
   *
   * @param lastUsedStep the last step a code was accepted for; that step and any before it are
   *     refused, whatever the code
   * @return the step the code belongs to, or -1 when it is not a code for any step still open
   */
  public static long verify(byte[] secret, String code, Instant now, long lastUsedStep) {
    if (code == null) return -1;
    String digits = code.replace(" ", "").trim();
    if (digits.length() != DIGITS || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) return -1;
    byte[] given = digits.getBytes(StandardCharsets.US_ASCII);
    long current = stepAt(now);
    long matched = -1;
    // Every step is computed whatever the outcome, so the answer takes as long for a wrong code as
    // for a right one.
    for (long step = current - WINDOW_STEPS; step <= current + WINDOW_STEPS; step++) {
      boolean same =
          MessageDigest.isEqual(given, code(secret, step).getBytes(StandardCharsets.US_ASCII));
      if (same && step > lastUsedStep && matched < 0) matched = step;
    }
    return matched;
  }

  /** The URI an authenticator app reads from a QR code (Google's Key URI format). */
  public static String otpauthUri(String issuer, String account, byte[] secret) {
    String label = enc(issuer) + ":" + enc(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32(secret)
        + "&issuer="
        + enc(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + PERIOD_SECONDS;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** RFC 4648 base 32 without padding: how an authenticator app is given a secret by hand. */
  public static String base32(byte[] data) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
    return out.toString();
  }

  /**
   * Reads base 32, forgiving case, spaces and padding.
   *
   * @throws IllegalArgumentException for a character outside the alphabet
   */
  public static byte[] fromBase32(String text) {
    String clean = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) throw new IllegalArgumentException("not base 32");
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.put((byte) ((buffer >> (bits - 8)) & 0xff));
        bits -= 8;
      }
    }
    return out.array();
  }
}
