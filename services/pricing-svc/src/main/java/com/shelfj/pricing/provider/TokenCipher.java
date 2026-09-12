package com.shelfj.pricing.provider;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Encrypts the OAuth tokens HMRC issues for a taxpayer before they are stored (18.5). A token at
 * rest in plain text is a token anyone with the database can file returns with. The key is
 * configuration (golden rule #5); without one the HMRC provider cannot connect, and says so.
 */
@ApplicationScoped
public class TokenCipher {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject
  @ConfigProperty(name = "shelfj.mtd.token-key")
  Optional<String> keyConfig;

  private SecretKeySpec key;

  @PostConstruct
  void init() {
    keyConfig.filter(k -> !k.isBlank()).ifPresent(this::use);
  }

  /** Package-private so a test can supply a key without configuration. */
  void use(String base64Key) {
    byte[] raw = Base64.getDecoder().decode(base64Key.trim());
    if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
      throw new IllegalArgumentException(
          "shelfj.mtd.token-key must be 128, 192 or 256 bits, base64");
    }
    key = new SecretKeySpec(raw, "AES");
  }

  /**
   * @return whether a key is configured, and so whether tokens can be held
   */
  public boolean isConfigured() {
    return key != null;
  }

  /** AES-GCM with a fresh nonce, base64 of nonce + ciphertext. */
  public String encrypt(String plain) {
    if (key == null) {
      throw new IllegalStateException("shelfj.mtd.token-key is not configured");
    }
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("token encryption failed", e);
    }
  }

  /** The reverse; a tampered value fails authentication and is refused. */
  public String decrypt(String cipherText) {
    if (key == null) {
      throw new IllegalStateException("shelfj.mtd.token-key is not configured");
    }
    try {
      byte[] all = Base64.getDecoder().decode(cipherText);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
      return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("stored token could not be read", e);
    }
  }
}
