package com.shelfj.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for iam-svc. DTOs are the API contract (golden rule #10). No tenant fields
 * in requests.
 *
 * <p>Ids are carried as {@code String} rather than {@code UUID} so the JSON contract stays stable.
 * Bean Validation annotations on the request records are what {@code Validations.validate} enforces
 * at the boundary.
 */
public final class Dtos {

  private Dtos() {}

  /** Customer self-signup. */
  @Schema(name = "RegisterRequest", description = "Customer self-signup.")
  public record RegisterRequest(
      @Schema(description = "Unique login email.") @Email @NotBlank String email,
      @Schema(description = "Plaintext password (hashed server-side before storage).")
          @NotBlank
          @Size(min = 8, max = 100)
          String password,
      @Schema(description = "Optional contact phone number.") String phone) {}

  /**
   * Admin provisions a staff account by email (find-or-create). The admin supplies the initial
   * password and shares it with the new staff member out-of-band; it is never echoed back in the
   * response.
   */
  @Schema(
      name = "ProvisionStaffRequest",
      description =
          "Admin find-or-create of a staff account by email. tenantId is taken from the caller's"
              + " JWT, never from this body.")
  public record ProvisionStaffRequest(
      @Schema(description = "Staff member's login email.") @Email @NotBlank String email,
      @Schema(
              description =
                  "Initial password, shared with the new staff member out-of-band. Never echoed"
                      + " back in the response.")
          @NotBlank
          @Size(min = 8, max = 100)
          String password) {}

  /** Result of staff provisioning: the userId to assign a store role to. */
  @Schema(
      name = "ProvisionStaffResponse",
      description = "The userId to assign a store role to via tenant-svc.")
  public record ProvisionStaffResponse(
      @Schema(description = "UUID of the staff user.") String userId,
      String email,
      @Schema(description = "True if a new user was created; false if one already existed.")
          boolean created) {}

  /** Login with email + password. */
  @Schema(name = "LoginRequest")
  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  /** Refresh access token. */
  @Schema(name = "RefreshRequest")
  public record RefreshRequest(
      @Schema(description = "A previously issued, still-valid refresh token.") @NotBlank
          String refreshToken) {}

  /** Logout / revoke a refresh token. */
  @Schema(name = "LogoutRequest")
  public record LogoutRequest(
      @Schema(description = "The refresh token to revoke.") @NotBlank String refreshToken) {}

  /** Token pair returned on register/login/refresh. */
  @Schema(name = "TokenResponse", description = "Access/refresh token pair.")
  public record TokenResponse(
      @Schema(description = "Short-lived JWT used as the Authorization: Bearer credential.")
          String accessToken,
      @Schema(description = "Long-lived token used to mint a new access token via /auth/refresh.")
          String refreshToken,
      @Schema(description = "Always \"Bearer\".") String tokenType,
      @Schema(description = "Access token lifetime in seconds from issuance.")
          long expiresInSeconds) {
    /**
     * Builds a {@code Bearer} token pair.
     *
     * @param access the short-lived access token
     * @param refresh the long-lived refresh token
     * @param ttl the access token's lifetime in seconds from issuance
     * @return the response with {@code tokenType} fixed to {@code Bearer}
     */
    public static TokenResponse bearer(String access, String refresh, long ttl) {
      return new TokenResponse(access, refresh, "Bearer", ttl);
    }
  }

  /** The account holder confirming, with their password, that the account should be deleted. */
  @Schema(name = "DeleteAccountRequest")
  public record DeleteAccountRequest(
      @Schema(description = "The account's password, re-verified before deletion.") @NotBlank
          String password) {}

  /** Change password (authenticated user only). */
  @Schema(name = "ChangePasswordRequest")
  public record ChangePasswordRequest(
      @Schema(description = "The user's current password, re-verified before the change.") @NotBlank
          String currentPassword,
      @Schema(description = "The new password to set.") @NotBlank @Size(min = 8, max = 100)
          String newPassword) {}

  /** Current principal (GET /auth/me). */
  @Schema(name = "MeResponse", description = "The authenticated caller's identity and roles.")
  public record MeResponse(
      String userId,
      @Schema(description = "Null for platform-admin users, who are not tenant-scoped.")
          String tenantId,
      @Schema(description = "CUSTOMER, STAFF, or PLATFORM_ADMIN.") String type,
      @Schema(description = "Role names granted to this user, e.g. OWNER, MANAGER, PLATFORM_ADMIN.")
          java.util.List<String> roles,
      String email,
      String phone,
      @Schema(description = "Account status, e.g. ACTIVE, DISABLED.") String status,
      String createdAt) {}

  // ── Gap #45: POS session idle timeout ─────────────────────────────────────

  @Schema(name = "StartPosSessionRequest")
  public record StartPosSessionRequest(
      @Schema(description = "UUID of the store this cashier session is opened at.") @NotBlank
          String storeId,
      @Schema(description = "Idle timeout override in seconds; falls back to the store default.")
          Integer idleTimeoutSeconds) {}

  @Schema(name = "PosSessionResponse")
  public record PosSessionResponse(
      String id,
      String tenantId,
      String userId,
      String storeId,
      String startedAt,
      @Schema(description = "Timestamp of the last recorded activity heartbeat.")
          String lastActivityAt,
      @Schema(description = "Null while the session is still open.") String endedAt,
      int idleTimeoutSeconds,
      @Schema(description = "ACTIVE, ENDED, or EXPIRED.") String status) {}

  @Schema(name = "IdleSweepResult", description = "Result of a POS idle-timeout sweep.")
  public record IdleSweepResult(
      @Schema(description = "Number of sessions force-expired by this sweep.")
          int sessionsExpired) {}
}
