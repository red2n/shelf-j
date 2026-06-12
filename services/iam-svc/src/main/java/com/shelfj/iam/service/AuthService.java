package com.shelfj.iam.service;

import com.shelfj.iam.auth.JwtService;
import com.shelfj.iam.auth.Passwords;
import com.shelfj.iam.auth.Tokens;
import com.shelfj.iam.config.ServiceConfig;
import com.shelfj.iam.domain.User;
import com.shelfj.iam.dto.Dtos.TokenResponse;
import com.shelfj.iam.repo.RefreshTokenRepository;
import com.shelfj.iam.repo.UserRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    // email is user input — esc() prevents a quoted-local-part address from injecting JSON
    String payload =
        """
                {"eventId":"%s","eventType":"UserRegistered","tenantId":null,"aggregateId":"%s",\
                "occurredAt":"%s","email":"%s","type":"CUSTOMER"}"""
            .formatted(
                UUID.randomUUID(),
                userId,
                Instant.now(),
                com.shelfj.events.EventPayload.esc(email));
    var outbox =
        new OutboxRow("UserRegistered", "shelfj.iam.user-registered", null, userId, payload);

    users.createUserWithOutbox(user, "CUSTOMER", outbox);
    users.audit(null, userId, "USER_REGISTERED", email);

    return issueTokens(user);
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
        users.audit(user.tenantId(), user.id(), "LOGIN_OK", email);
        return issueTokens(user);
      }
    }
    if (!candidates.isEmpty()) {
      User first = candidates.get(0);
      users.audit(first.tenantId(), first.id(), "LOGIN_FAILED", email);
    }
    throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
  }

  /** Rotate a refresh token → new access + new refresh token; old one is revoked. */
  public TokenResponse refresh(String refreshToken) {
    String hash = Tokens.hash(refreshToken);
    UUID userId =
        refreshTokens
            .validate(hash)
            .orElseThrow(
                () ->
                    ApiException.unauthorized(
                        "INVALID_REFRESH", "Refresh token invalid or expired"));
    refreshTokens.revoke(hash); // rotation: single-use
    User user =
        users
            .findById(userId)
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_REFRESH", "User no longer exists"));
    return issueTokens(user);
  }

  /** Revoke a refresh token (logout). */
  public void logout(String refreshToken) {
    refreshTokens.revoke(Tokens.hash(refreshToken));
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
    String access = jwt.issueAccessToken(user.id(), user.tenantId(), user.type(), roles);

    String refresh = Tokens.newOpaqueToken();
    refreshTokens.store(
        user.id(), Tokens.hash(refresh), Instant.now().plusSeconds(config.refreshTtlSeconds()));

    return TokenResponse.bearer(access, refresh, config.accessTtlSeconds());
  }
}
