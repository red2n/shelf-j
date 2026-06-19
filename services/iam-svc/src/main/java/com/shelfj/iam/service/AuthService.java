package com.shelfj.iam.service;

import com.shelfj.iam.auth.JwtService;
import com.shelfj.iam.auth.Passwords;
import com.shelfj.iam.auth.Tokens;
import com.shelfj.iam.config.ServiceConfig;
import com.shelfj.iam.domain.User;
import com.shelfj.iam.dto.Dtos.ProvisionStaffResponse;
import com.shelfj.iam.dto.Dtos.TokenResponse;
import com.shelfj.iam.repo.RefreshTokenRepository;
import com.shelfj.iam.repo.UserRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Core authentication logic: register, login, refresh, logout. The brain of iam-svc (golden rule
 * #9).
 *
 * <p>On register, the user row and the {@code UserRegistered} outbox event are written in one
 * transaction (golden rule #6). Refresh tokens are opaque + rotated on every refresh; only their
 * hash is stored.
 */
@ApplicationScoped
public class AuthService {

  @Inject ServiceConfig config;
  @Inject Passwords passwords;
  @Inject JwtService jwt;
  @Inject UserRepository users;
  @Inject RefreshTokenRepository refreshTokens;

  /** Customer self-signup → creates a CUSTOMER (global, tenantId null) and returns a token pair. */
  public TokenResponse register(String email, String password, String phone) {
    String hash = passwords.hash(password);
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    var user =
        new User(
            userId, null, User.TYPE_CUSTOMER, email, phone, hash, User.STATUS_ACTIVE, now, now);

    String payload =
        Json.createObjectBuilder()
            .add("eventId", UUID.randomUUID().toString())
            .add("eventType", "UserRegistered")
            .addNull("tenantId")
            .add("aggregateId", userId.toString())
            .add("occurredAt", Instant.now().toString())
            .add("email", email)
            .add("type", "CUSTOMER")
            .build()
            .toString();
    var outbox =
        new OutboxRow("UserRegistered", "shelfj.iam.user-registered", null, userId, payload);

    users.createUserWithOutbox(user, "CUSTOMER", outbox);
    users.audit(null, userId, "USER_REGISTERED", email);

    return issueTokens(user);
  }

  /**
   * Admin-driven staff provisioning: find an existing account by email or create one, returning the
   * userId the caller (tenant-svc) then assigns a store role to. The actual tenant + role binding
   * happens asynchronously when tenant-svc publishes {@code StaffAssigned}; here we only ensure an
   * account exists so the admin never has to know a UUID.
   *
   * <ul>
   *   <li>Email already in this tenant (or still global / unbound) → reuse that account.
   *   <li>Email bound to a different tenant → 409 (can't poach another business's user).
   *   <li>No such email → create an ACTIVE account with the given or a generated temp password.
   * </ul>
   */
  public ProvisionStaffResponse provisionStaff(UUID tenantId, String email, String rawPassword) {
    var candidates = users.findAllByEmail(email);
    for (User u : candidates) {
      if (u.tenantId() == null || u.tenantId().equals(tenantId)) {
        users.audit(tenantId, u.id(), "STAFF_PROVISIONED_REUSE", email);
        return new ProvisionStaffResponse(u.id().toString(), email, false);
      }
    }
    if (!candidates.isEmpty()) {
      throw new ApiException(
          409,
          "EMAIL_IN_OTHER_TENANT",
          "That email already belongs to another business",
          java.util.List.of());
    }

    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    var user =
        new User(
            userId,
            null,
            User.TYPE_STAFF,
            email,
            null,
            passwords.hash(rawPassword),
            User.STATUS_ACTIVE,
            now,
            now);
    String payload =
        Json.createObjectBuilder()
            .add("eventId", UUID.randomUUID().toString())
            .add("eventType", "UserRegistered")
            .addNull("tenantId")
            .add("aggregateId", userId.toString())
            .add("occurredAt", now.toString())
            .add("email", email)
            .add("type", "STAFF")
            .build()
            .toString();
    var outbox =
        new OutboxRow("UserRegistered", "shelfj.iam.user-registered", null, userId, payload);
    users.createUserWithOutbox(user, "STAFF", outbox);
    users.audit(tenantId, userId, "STAFF_PROVISIONED", email);
    return new ProvisionStaffResponse(userId.toString(), email, true);
  }

  /**
   * Login with email + password. Email is unique only per tenant scope (a user's row moves out of
   * the NULL scope once a TenantCreated/StaffAssigned event stamps their tenant), so we search all
   * scopes and let the password disambiguate.
   */
  public TokenResponse login(String email, String password) {
    var candidates = users.findAllByEmail(email);
    for (User user : candidates) {
      if (User.STATUS_ACTIVE.equals(user.status())
          && passwords.verify(user.passwordHash(), password)) {
        // A staff user whose tenant has been deactivated must not be able to log in, even with the
        // right password and an ACTIVE user row. (Customers carry tenantId=null and are
        // unaffected.)
        if (user.tenantId() != null && !users.isTenantActive(user.tenantId())) {
          users.audit(user.tenantId(), user.id(), "LOGIN_BLOCKED_TENANT_INACTIVE", email);
          throw ApiException.forbidden(
              "TENANT_INACTIVE", "This business account is suspended. Contact support.");
        }
        users.audit(user.tenantId(), user.id(), "LOGIN_OK", email);
        return issueTokens(user);
      }
    }
    if (candidates.isEmpty()) {
      // Equalize timing with the verify above so response time doesn't reveal whether the
      // email exists (account-enumeration oracle).
      passwords.burn(password);
    } else {
      User first = candidates.get(0);
      users.audit(first.tenantId(), first.id(), "LOGIN_FAILED", email);
    }
    throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
  }

  /** Rotate a refresh token → new access + new refresh token; old one is revoked. */
  public TokenResponse refresh(String refreshToken) {
    String hash = Tokens.hash(refreshToken);
    // Atomic consume: validate + revoke in one statement, so a token can be rotated exactly once
    // even under concurrent requests.
    UUID userId =
        refreshTokens
            .consume(hash)
            .orElseThrow(
                () -> {
                  // Reuse of an already-revoked token is the classic stolen-token signal: either
                  // the attacker or the legitimate user holds a now-dead token. Revoke the whole
                  // session family so the holder of the stolen token is cut off too.
                  refreshTokens
                      .ownerOfRevoked(hash)
                      .ifPresent(
                          owner -> {
                            refreshTokens.revokeAllForUser(owner);
                            users.audit(
                                null,
                                owner,
                                "REFRESH_REUSE_DETECTED",
                                "revoked token presented - all sessions revoked");
                          });
                  return ApiException.unauthorized(
                      "INVALID_REFRESH", "Refresh token invalid or expired");
                });
    User user =
        users
            .findById(userId)
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_REFRESH", "User no longer exists"));
    // Block token refresh for a suspended tenant too — otherwise a staff member with a live refresh
    // token could keep minting access tokens after their business was deactivated.
    if (user.tenantId() != null && !users.isTenantActive(user.tenantId())) {
      throw ApiException.forbidden(
          "TENANT_INACTIVE", "This business account is suspended. Contact support.");
    }
    return issueTokens(user);
  }

  /** Revoke a refresh token (logout). */
  public void logout(String refreshToken) {
    refreshTokens.revoke(Tokens.hash(refreshToken));
  }

  /**
   * One-shot bootstrap: creates the first PLATFORM_ADMIN. Rejects if one already exists so the
   * endpoint is safe to leave enabled after first use.
   */
  public UUID bootstrapAdmin(String email, String rawPassword) {
    if (users.platformAdminExists()) {
      throw new ApiException(
          409,
          "BOOTSTRAP_ALREADY_DONE",
          "A PLATFORM_ADMIN already exists. Use the login endpoint.",
          java.util.List.of(),
          null);
    }
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    var user =
        new User(
            userId,
            null,
            User.TYPE_STAFF,
            email,
            null,
            passwords.hash(rawPassword),
            User.STATUS_ACTIVE,
            now,
            now);
    users.createPlatformAdmin(user);
    users.audit(null, userId, "PLATFORM_ADMIN_BOOTSTRAPPED", email);
    return userId;
  }

  /** Fetch a user by id — used by MeResource to resolve the current principal. */
  public User lookupUser(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User no longer exists"));
  }

  /** Change password for an authenticated user (requires current password). */
  public void changePassword(UUID userId, String currentPassword, String newPassword) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    if (!passwords.verify(user.passwordHash(), currentPassword)) {
      throw ApiException.unauthorized("INVALID_CREDENTIALS", "Current password is incorrect");
    }
    users.updatePassword(userId, passwords.hash(newPassword));
    // Revoke every outstanding refresh token: a password change must invalidate sessions that
    // may have been established with the old (possibly compromised) credentials.
    refreshTokens.revokeAllForUser(userId);
    users.audit(user.tenantId(), userId, "PASSWORD_CHANGED", user.email());
  }

  // --- helpers ---

  private TokenResponse issueTokens(User user) {
    Set<String> roles = users.rolesOf(user.id());
    Set<UUID> storeIds = users.storeScopeOf(user.id());
    String access = jwt.issueAccessToken(user.id(), user.tenantId(), user.type(), roles, storeIds);

    String refresh = Tokens.newOpaqueToken();
    refreshTokens.store(
        user.id(), Tokens.hash(refresh), Instant.now().plusSeconds(config.refreshTtlSeconds()));

    return TokenResponse.bearer(access, refresh, config.accessTtlSeconds());
  }
}
