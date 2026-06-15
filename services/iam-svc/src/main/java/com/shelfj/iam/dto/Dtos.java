package com.shelfj.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request/response DTOs for iam-svc. DTOs are the API contract (golden rule #10). No tenant fields
 * in requests.
 */
public final class Dtos {

  private Dtos() {}

  /** Customer self-signup. */
  public record RegisterRequest(
      @Email @NotBlank String email,
      @NotBlank @Size(min = 8, max = 100) String password,
      String phone) {}

  /**
   * Admin provisions a staff account by email (find-or-create), so admins never handle raw user
   * UUIDs. {@code password} is optional — when blank, iam-svc generates a temporary one and returns
   * it so the admin can share it with the new staff member.
   */
  public record ProvisionStaffRequest(
      @Email @NotBlank String email, @Size(min = 8, max = 100) String password) {}

  /**
   * Result of staff provisioning: the user id to assign, and (only when newly created) the temp
   * password.
   */
  public record ProvisionStaffResponse(
      String userId, String email, boolean created, String tempPassword) {}

  /** Login with email + password. */
  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  /** Refresh access token. */
  public record RefreshRequest(@NotBlank String refreshToken) {}

  /** Logout / revoke a refresh token. */
  public record LogoutRequest(@NotBlank String refreshToken) {}

  /** Token pair returned on register/login/refresh. */
  public record TokenResponse(
      String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
    public static TokenResponse bearer(String access, String refresh, long ttl) {
      return new TokenResponse(access, refresh, "Bearer", ttl);
    }
  }

  /** Change password (authenticated user only). */
  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {}

  /** Current principal (GET /auth/me). */
  public record MeResponse(
      String userId,
      String tenantId,
      String type,
      java.util.List<String> roles,
      String email,
      String phone,
      String status,
      String createdAt) {}

  // ── Gap #45: POS session idle timeout ─────────────────────────────────────

  public record StartPosSessionRequest(@NotBlank String storeId, Integer idleTimeoutSeconds) {}

  public record PosSessionResponse(
      String id,
      String tenantId,
      String userId,
      String storeId,
      String startedAt,
      String lastActivityAt,
      String endedAt,
      int idleTimeoutSeconds,
      String status) {}

  public record IdleSweepResult(int sessionsExpired) {}
}
