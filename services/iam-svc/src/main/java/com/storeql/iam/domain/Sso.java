package com.storeql.iam.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Rows of the single sign-on tables (20.x, SSO over OpenID Connect). */
public final class Sso {

  private Sso() {}

  /**
   * A business's identity provider.
   *
   * @param slug the sign-in name its staff type
   * @param issuer the provider's issuer, exactly as its discovery document states it
   * @param clientSecretSealed the client secret, sealed; null when it has to be entered again
   * @param requiredTiers the tiers a password no longer signs in
   * @param requireVerifiedEmail whether a first sign-in is matched by email only when the provider
   *     says it verified the address
   */
  public record Connection(
      UUID id,
      UUID tenantId,
      String slug,
      String issuer,
      String clientId,
      String clientSecretSealed,
      boolean enabled,
      Set<String> requiredTiers,
      boolean requireVerifiedEmail,
      Instant updatedAt) {

    public Connection {
      requiredTiers = Set.copyOf(requiredTiers);
    }

    /** Whether a password is refused to a login holding these roles. */
    public boolean requiredOf(Set<String> roles) {
      return enabled && roles.stream().anyMatch(requiredTiers::contains);
    }
  }

  /**
   * One of the provider's people, and the login they are.
   *
   * @param subject the provider's {@code sub}
   * @param email the address the provider asserted at the last sign-in
   */
  public record Identity(
      UUID id,
      UUID tenantId,
      UUID userId,
      String issuer,
      String subject,
      String email,
      Instant linkedAt,
      Instant lastLoginAt) {}

  /**
   * A sign-in in flight.
   *
   * @param codeVerifierSealed this service's PKCE verifier for the provider, sealed
   * @param appChallenge S256 of the verifier the app kept, which it presents with the ticket
   * @param userId who the provider proved, once it has
   * @param amr how, as the session will record it
   */
  public record Flow(
      UUID id,
      UUID tenantId,
      UUID connectionId,
      String nonce,
      String codeVerifierSealed,
      String appChallenge,
      String returnTo,
      UUID userId,
      String amr,
      Instant expiresAt) {}
}
