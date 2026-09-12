package com.shelfj.order.fiscal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The key this software signs Portuguese documents with (18.5). External configuration, never in
 * the image (golden rule #5): the producer generates the pair, keeps the private half here and
 * registers the public half with the AT, which certifies the software and issues the number that
 * goes on every document.
 */
@ApplicationScoped
public class PtSigningKey {

  // Optional, not defaultValue = "": MicroProfile Config treats an empty default as no default at
  // all and fails deployment when the key is absent — which is every environment that has not
  // been certified for Portugal, including the tests.
  @Inject
  @ConfigProperty(name = "shelfj.fiscal.pt.private-key")
  Optional<String> privateKeyConfig;

  /** The key version the AT knows this key as: SAF-T's HashControl. */
  @Inject
  @ConfigProperty(name = "shelfj.fiscal.pt.key-version", defaultValue = "1")
  String keyVersion;

  private PrivateKey privateKey;
  private PublicKey publicKey;

  @PostConstruct
  void init() {
    String pem = privateKeyConfig.orElse("").trim();
    if (!pem.isEmpty()) {
      privateKey = PtSignature.loadPrivateKey(pem);
      publicKey = PtSignature.publicKeyOf(privateKey);
    }
  }

  /** Package-private so a test can configure a key without config. */
  void use(PrivateKey key) {
    privateKey = key;
    publicKey = PtSignature.publicKeyOf(key);
  }

  /**
   * @return whether a key is configured, and so whether a store may be placed under PT_SAFT
   */
  public boolean isConfigured() {
    return privateKey != null;
  }

  /**
   * @return the signing key
   * @throws IllegalStateException when none is configured
   */
  public PrivateKey privateKey() {
    if (privateKey == null) {
      throw new IllegalStateException("shelfj.fiscal.pt.private-key is not configured");
    }
    return privateKey;
  }

  /**
   * @return the public half, base64 X.509 DER, for the audit and the settings screen; null when no
   *     key is configured
   */
  public String publicKeyBase64() {
    return publicKey == null ? null : Base64.getEncoder().encodeToString(publicKey.getEncoded());
  }

  /**
   * @return the HashControl value
   */
  public String keyVersion() {
    return keyVersion;
  }
}
