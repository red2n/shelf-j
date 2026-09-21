package com.storeql.iam.domain;

import java.time.Instant;
import java.util.UUID;

/** Rows of the second-factor tables (20.12). */
public final class Mfa {

  public static final String TOTP_PENDING = "PENDING";
  public static final String TOTP_ACTIVE = "ACTIVE";
  public static final String KIND_LOGIN = "LOGIN";
  public static final String KIND_PASSKEY_REGISTRATION = "PASSKEY_REGISTRATION";

  private Mfa() {}

  /**
   * An authenticator app's shared secret.
   *
   * @param secretSealed the secret, sealed
   * @param status PENDING until its owner has proved the app shows the right code
   * @param lastUsedStep the last time step a code was accepted for
   */
  public record Totp(UUID userId, String secretSealed, String status, long lastUsedStep) {
    public boolean active() {
      return TOTP_ACTIVE.equals(status);
    }
  }

  /**
   * A passkey.
   *
   * @param credentialId base64url
   * @param publicKey the COSE key, base64
   */
  public record Passkey(
      UUID id,
      UUID userId,
      String credentialId,
      String publicKey,
      long signCount,
      String name,
      Instant createdAt,
      Instant lastUsedAt) {}

  /**
   * A sign-in that owes its second factor, or a passkey registration in progress.
   *
   * @param webauthnChallenge base64url, when a passkey ceremony has been opened on it
   */
  public record Challenge(
      UUID id,
      UUID userId,
      String kind,
      String webauthnChallenge,
      int attempts,
      Instant expiresAt) {}
}
