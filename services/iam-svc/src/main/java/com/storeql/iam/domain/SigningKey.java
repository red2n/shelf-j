package com.storeql.iam.domain;

import java.time.Instant;

/**
 * A token signing key (20.15). The public half is published by key id; the private half is kept
 * sealed and only iam-svc can unseal it.
 *
 * @param kid the key id carried in a token's header and in the published key set
 * @param algorithm {@code RS256}
 * @param publicKey X.509 SubjectPublicKeyInfo, base64
 * @param privateKeySealed PKCS#8, sealed; empty once the key is retired
 * @param status {@link #ACTIVE} signs; {@link #RETIRING} still verifies; {@link #RETIRED} does
 *     neither
 */
public record SigningKey(
    String kid,
    String algorithm,
    String publicKey,
    String privateKeySealed,
    String status,
    Instant createdAt,
    Instant retiringAt,
    Instant retiredAt) {
  public static final String ALGORITHM_RS256 = "RS256";
  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRING = "RETIRING";
  public static final String RETIRED = "RETIRED";
}
