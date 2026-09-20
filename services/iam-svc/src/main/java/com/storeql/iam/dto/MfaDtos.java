package com.storeql.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response shapes of the second-factor routes (20.12). */
public final class MfaDtos {

  private MfaDtos() {}

  /** Answering a waiting sign-in's second factor. */
  @Schema(name = "MfaLoginRequest")
  public record MfaLoginRequest(
      @Schema(description = "The mfaToken the sign-in answered with.") @NotBlank @Size(max = 200)
          String mfaToken,
      @Schema(description = "TOTP, RECOVERY_CODE or PASSKEY.") @NotBlank @Size(max = 20)
          String method,
      @Schema(description = "The six digits, or the recovery code.") @Size(max = 40) String code,
      @Schema(description = "PASSKEY: the assertion the browser returned.")
          PasskeyAssertion assertion) {}

  /** A passkey's answer to a sign-in challenge; every field base64url, as the browser gives it. */
  @Schema(name = "PasskeyAssertion")
  public record PasskeyAssertion(
      @NotBlank @Size(max = 2000) String credentialId,
      @NotBlank @Size(max = 24000) String clientDataJson,
      @NotBlank @Size(max = 24000) String authenticatorData,
      @NotBlank @Size(max = 24000) String signature) {}

  /** Asking for a passkey challenge on a waiting sign-in. */
  @Schema(name = "MfaTokenRequest")
  public record MfaTokenRequest(@NotBlank @Size(max = 200) String mfaToken) {}

  /** What {@code navigator.credentials.get} needs. */
  @Schema(name = "PasskeyRequestOptions")
  public record PasskeyRequestOptions(
      String challenge,
      String rpId,
      List<String> allowCredentials,
      String userVerification,
      long timeoutMillis) {
    public PasskeyRequestOptions {
      allowCredentials = List.copyOf(allowCredentials);
    }
  }

  /** A new authenticator secret, to be confirmed with a code before it counts. */
  @Schema(name = "TotpEnrolment")
  public record TotpEnrolment(
      @Schema(description = "The secret in base 32, for typing into an app by hand.") String secret,
      @Schema(description = "The same as an otpauth:// URI, for a QR code.") String otpauthUri) {}

  /** A code, to confirm an authenticator or to prove a second factor. */
  @Schema(name = "CodeRequest")
  public record CodeRequest(@NotBlank @Size(max = 40) String code) {}

  /** The password again, before something is taken away. */
  @Schema(name = "PasswordRequest")
  public record PasswordRequest(@NotBlank @Size(max = 128) String password) {}

  /**
   * What setting a factor up answers: the recovery codes, shown this once, when they were made —
   * and the real token pair when the caller held only an enrolment token.
   */
  @Schema(name = "FactorEnrolled")
  public record FactorEnrolled(List<String> recoveryCodes, Dtos.TokenResponse tokens) {
    public FactorEnrolled {
      // Absent rather than empty when none were made: a client shows the codes it is given.
      recoveryCodes =
          recoveryCodes == null || recoveryCodes.isEmpty() ? null : List.copyOf(recoveryCodes);
    }
  }

  /** What {@code navigator.credentials.create} needs, and the token naming this registration. */
  @Schema(name = "PasskeyCreationOptions")
  public record PasskeyCreationOptions(
      String registrationToken,
      String challenge,
      String rpId,
      String rpName,
      String userId,
      String userName,
      List<Long> algorithms,
      List<String> excludeCredentials,
      String userVerification,
      String attestation,
      long timeoutMillis) {
    public PasskeyCreationOptions {
      algorithms = List.copyOf(algorithms);
      excludeCredentials = List.copyOf(excludeCredentials);
    }
  }

  /** A passkey the browser has just made. */
  @Schema(name = "PasskeyRegistration")
  public record PasskeyRegistration(
      @NotBlank @Size(max = 200) String registrationToken,
      @Schema(description = "What its owner calls it: \"Ana's laptop\".") @NotBlank @Size(max = 60)
          String name,
      @NotBlank @Size(max = 24000) String clientDataJson,
      @NotBlank @Size(max = 24000) String attestationObject) {}

  /** A passkey as its owner sees it: never the key. */
  @Schema(name = "PasskeyView")
  public record PasskeyView(String id, String name, String createdAt, String lastUsedAt) {}

  /** Where a login stands with second factors. */
  @Schema(name = "MfaStatus")
  public record MfaStatus(
      @Schema(description = "An authenticator app is set up and confirmed.") boolean totp,
      List<PasskeyView> passkeys,
      int recoveryCodesLeft,
      @Schema(description = "Whether this login's business, or the platform, requires one.")
          boolean required) {
    public MfaStatus {
      passkeys = List.copyOf(passkeys);
    }
  }

  /** A business's rule: which tiers of its staff must have a second factor. */
  @Schema(name = "MfaPolicy")
  public record MfaPolicy(
      @Schema(description = "Tiers that must: any of OWNER, MANAGER, STOREKEEPER, CASHIER.")
          @NotNull
          List<@NotBlank @Size(max = 30) String> requiredTiers) {
    public MfaPolicy {
      requiredTiers = requiredTiers == null ? null : List.copyOf(requiredTiers);
    }
  }
}
