package com.shelfj.order.einvoice;

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
 * Seals a business's provider credentials for the database, under the deployment's key ({@code
 * shelfj.einvoice.secrets-key}: 16, 24 or 32 bytes, base64). AES-GCM with a fresh nonce each time,
 * so two identical secrets never look alike and a byte changed is a secret refused. Without the key
 * nothing can be sealed, and a provider that needs one cannot be chosen.
 */
@ApplicationScoped
public class Secrets {

  static final String PREFIX = "v1:";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  @Inject
  @ConfigProperty(name = "shelfj.einvoice.secrets-key")
  Optional<String> keyConfig;

  private SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  @PostConstruct
  void init() {
    key = keyConfig.filter(k -> !k.isBlank()).map(Secrets::keyOf).orElse(null);
  }

  /** For tests: a sealer with the key given, or none. */
  public static Secrets forTest(String keyBase64) {
    Secrets s = new Secrets();
    s.key = keyBase64 == null ? null : keyOf(keyBase64);
    return s;
  }

  private static SecretKeySpec keyOf(String base64) {
    byte[] bytes = Base64.getDecoder().decode(base64.strip());
    if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
      throw new IllegalArgumentException("shelfj.einvoice.secrets-key must be 16, 24 or 32 bytes");
    }
    return new SecretKeySpec(bytes, "AES");
  }

  public boolean isConfigured() {
    return key != null;
  }

  /**
   * @return the secret sealed for the database
   * @throws IllegalStateException when no key is configured
   */
  public String seal(String plain) {
    if (key == null) throw new IllegalStateException("shelfj.einvoice.secrets-key is not set");
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not seal the secret", e);
    }
  }

  /**
   * @return the secret as it was, or null for null
   * @throws IllegalStateException when no key is configured, or the sealed text was tampered with
   */
  public String open(String sealed) {
    if (sealed == null) return null;
    if (key == null) throw new IllegalStateException("shelfj.einvoice.secrets-key is not set");
    if (!sealed.startsWith(PREFIX)) throw new IllegalStateException("not a sealed secret");
    try {
      byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
      byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("the sealed secret could not be opened", e);
    }
  }
}
