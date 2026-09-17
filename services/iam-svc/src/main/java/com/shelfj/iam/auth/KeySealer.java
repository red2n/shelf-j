package com.shelfj.iam.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a signing key's private half for the database (20.15): AES-256-GCM under a key derived from
 * the deployment's sealing secret, a fresh nonce each time, so a byte changed is a key refused. The
 * secret mints nothing by itself — it only opens what iam-svc's own database holds.
 */
public final class KeySealer {

  private static final String PREFIX = "v1:";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param secret the deployment's sealing secret, at least 32 characters
   */
  public KeySealer(String secret) {
    this(secret, "shelfj-signing-key-seal:");
  }

  /**
   * A sealer for another kind of secret (20.12: authenticator secrets): the same deployment secret,
   * a different derived key, so what seals one kind never opens another.
   *
   * @param secret the deployment's sealing secret, at least 32 characters
   * @param purpose what the derived key is for; part of the derivation
   */
  public KeySealer(String secret, String purpose) {
    if (secret == null || secret.trim().length() < 32) {
      throw new IllegalStateException(
          "shelfj.jwt.secret must be set and at least 32 characters: it seals the token signing"
              + " keys at rest");
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((purpose + secret).getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String seal(byte[] plain) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plain);
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not seal the signing key", e);
    }
  }

  /**
   * @throws IllegalStateException when the value was sealed under another secret or was altered
   */
  public byte[] open(String sealed) {
    if (sealed == null || !sealed.startsWith(PREFIX)) {
      throw new IllegalStateException("not a sealed signing key");
    }
    try {
      byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
      return cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "the signing key could not be unsealed: was shelfj.jwt.secret changed?", e);
    }
  }
}
