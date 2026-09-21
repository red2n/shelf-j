package com.storeql.iam.service;

import com.storeql.iam.auth.KeySealer;
import com.storeql.iam.auth.Passwords;
import com.storeql.iam.auth.Tokens;
import com.storeql.iam.config.ServiceConfig;
import com.storeql.iam.domain.Mfa;
import com.storeql.iam.domain.User;
import com.storeql.iam.dto.MfaDtos;
import com.storeql.iam.mfa.RecoveryCodes;
import com.storeql.iam.mfa.Totp;
import com.storeql.iam.mfa.WebAuthn;
import com.storeql.iam.repo.MfaRepository;
import com.storeql.iam.repo.RefreshTokenRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Second factors (20.12; NIST SP 800-63B-4 AAL2): an authenticator app, passkeys — the
 * phishing-resistant one — and recovery codes for the day the phone is lost. A login that has a
 * factor is always asked for it; a business may require one of its staff by tier, and the platform
 * requires one of its administrators.
 *
 * <p>What is kept: the app's secret sealed, recovery codes hashed, a passkey's public key. A
 * waiting sign-in is a random token whose hash is kept for minutes and which a handful of wrong
 * answers ends, so the six digits cannot be guessed at leisure.
 */
@ApplicationScoped
public class MfaService {

  public static final String METHOD_TOTP = "TOTP";
  public static final String METHOD_RECOVERY_CODE = "RECOVERY_CODE";
  public static final String METHOD_PASSKEY = "PASSKEY";

  /** The tiers a business may require a second factor of. */
  public static final Set<String> TIERS = Set.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");

  private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
  private static final int CHALLENGE_BYTES = 32;
  private static final long PASSKEY_TIMEOUT_MILLIS = 120_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject MfaRepository mfa;
  @Inject UserRepository users;
  @Inject RefreshTokenRepository refreshTokens;
  @Inject ServiceConfig config;
  @Inject Passwords passwords;

  private KeySealer sealer;

  @PostConstruct
  void init() {
    sealer = new KeySealer(config.jwtSecret(), "storeql-mfa-secret-seal:");
  }

  /**
   * How a second factor was proved.
   *
   * @param amr the RFC 8176 name of the method: {@code otp} or {@code hwk}
   */
  public record Proved(UUID userId, String amr) {}

  // ── where a login stands ────────────────────────────────────────────────────

  /** The factors this login can answer a sign-in with; empty when it has none. */
  public List<String> methods(UUID userId) {
    List<String> out = new ArrayList<>();
    if (mfa.totp(userId).filter(Mfa.Totp::active).isPresent()) out.add(METHOD_TOTP);
    if (!mfa.passkeys(userId).isEmpty()) out.add(METHOD_PASSKEY);
    if (!out.isEmpty() && mfa.recoveryCodesLeft(userId) > 0) out.add(METHOD_RECOVERY_CODE);
    return out;
  }

  /** Whether the platform, or this login's business, requires a second factor of it. */
  public boolean required(UUID tenantId, Set<String> roles) {
    if (roles.contains(PLATFORM_ADMIN)) return config.mfaPlatformAdminRequired();
    if (tenantId == null) return false;
    Set<String> tiers = mfa.requiredTiers(tenantId);
    return roles.stream().anyMatch(tiers::contains);
  }

  public MfaDtos.MfaStatus status(UUID userId, UUID tenantId, Set<String> roles) {
    return new MfaDtos.MfaStatus(
        mfa.totp(userId).filter(Mfa.Totp::active).isPresent(),
        mfa.passkeys(userId).stream().map(MfaService::view).toList(),
        mfa.recoveryCodesLeft(userId),
        required(tenantId, roles));
  }

  private static MfaDtos.PasskeyView view(Mfa.Passkey p) {
    return new MfaDtos.PasskeyView(
        p.id().toString(),
        p.name(),
        p.createdAt().toString(),
        p.lastUsedAt() == null ? null : p.lastUsedAt().toString());
  }

  // ── a sign-in that owes its second factor ───────────────────────────────────

  /** Opens the wait: the token goes to the client, its hash stays here. */
  public String openLogin(UUID userId) {
    String token = Tokens.newOpaqueToken();
    Instant now = Instant.now();
    mfa.createChallenge(
        userId,
        Mfa.KIND_LOGIN,
        Tokens.hash(token),
        null,
        now,
        now.plusSeconds(config.mfaChallengeTtlSeconds()));
    return token;
  }

  private Mfa.Challenge waiting(String mfaToken) {
    return mfa.openChallenge(Tokens.hash(mfaToken), Mfa.KIND_LOGIN, Instant.now())
        .orElseThrow(
            () ->
                ApiException.unauthorized(
                    "MFA_CHALLENGE_EXPIRED", "Sign in again: this sign-in is no longer waiting"));
  }

  /** The challenge a passkey signs for a waiting sign-in. */
  public MfaDtos.PasskeyRequestOptions passkeyOptions(String mfaToken) {
    Mfa.Challenge waiting = waiting(mfaToken);
    List<Mfa.Passkey> keys = mfa.passkeys(waiting.userId());
    if (keys.isEmpty()) {
      throw ApiException.badRequest("MFA_METHOD_NOT_ENROLLED", "This login has no passkey");
    }
    String challenge = newChallenge();
    mfa.setWebauthnChallenge(waiting.id(), challenge);
    return new MfaDtos.PasskeyRequestOptions(
        challenge,
        config.mfaRpId(),
        keys.stream().map(Mfa.Passkey::credentialId).toList(),
        "required",
        PASSKEY_TIMEOUT_MILLIS);
  }

  /**
   * Judges a second factor. A wrong answer is counted against the waiting sign-in; past the limit
   * it is over and the password has to be given again.
   *
   * @throws ApiException 401 for a wrong answer, an ended wait or a method the login lacks
   */
  public Proved verifyLogin(MfaDtos.MfaLoginRequest req) {
    Mfa.Challenge waiting = waiting(req.mfaToken());
    UUID userId = waiting.userId();
    Instant now = Instant.now();
    // Five guesses a sign-in, and the password buys another sign-in: without a limit over all of
    // them, whoever holds the password could grind the six digits out. Past it even the right
    // answer waits.
    if (mfa.recentFailures(userId, now.minusSeconds(config.mfaLockoutWindowSeconds()))
        >= config.mfaLockoutFailures()) {
      users.audit(null, userId, "MFA_LOCKED", null);
      throw new ApiException(
          429,
          "MFA_LOCKED",
          "Too many wrong answers for this login's second factor: try again later",
          List.of());
    }
    String amr;
    boolean ok;
    switch (req.method() == null ? "" : req.method()) {
      case METHOD_TOTP -> {
        amr = "otp";
        ok = totpMatches(userId, req.code(), now);
      }
      case METHOD_RECOVERY_CODE -> {
        amr = "otp";
        ok =
            RecoveryCodes.looksLikeOne(req.code())
                && mfa.useRecoveryCode(userId, RecoveryCodes.hash(req.code()), now);
        if (ok) users.audit(null, userId, "MFA_RECOVERY_CODE_USED", null);
      }
      case METHOD_PASSKEY -> {
        amr = "hwk";
        ok = passkeyMatches(waiting, req.assertion(), now);
      }
      default -> throw ApiException.badRequest("MFA_METHOD_UNKNOWN", "Not a second factor");
    }
    if (!ok) {
      int attempts = mfa.countAttempt(waiting.id());
      users.audit(null, userId, "MFA_LOGIN_FAILED", req.method());
      if (attempts >= config.mfaMaxAttempts()) mfa.consume(waiting.id(), now);
      throw ApiException.unauthorized("MFA_CODE_INVALID", "That did not match");
    }
    if (!mfa.consume(waiting.id(), now)) {
      throw ApiException.unauthorized(
          "MFA_CHALLENGE_EXPIRED", "Sign in again: this sign-in is no longer waiting");
    }
    users.audit(null, userId, "MFA_LOGIN_OK", req.method());
    return new Proved(userId, amr);
  }

  private boolean totpMatches(UUID userId, String code, Instant now) {
    Optional<Mfa.Totp> totp = mfa.totp(userId).filter(Mfa.Totp::active);
    if (totp.isEmpty()) return false;
    long step =
        Totp.verify(sealer.open(totp.get().secretSealed()), code, now, totp.get().lastUsedStep());
    // The step is claimed in the database, so two requests with the same code cannot both win.
    return step >= 0 && mfa.useTotpStep(userId, step, now);
  }

  private boolean passkeyMatches(Mfa.Challenge waiting, MfaDtos.PasskeyAssertion a, Instant now) {
    if (a == null || waiting.webauthnChallenge() == null) return false;
    Optional<Mfa.Passkey> key = mfa.passkey(waiting.userId(), a.credentialId());
    if (key.isEmpty()) return false;
    try {
      long count =
          WebAuthn.assertion(
              url(a.clientDataJson()),
              url(a.authenticatorData()),
              url(a.signature()),
              url(waiting.webauthnChallenge()),
              relyingParty(),
              Base64.getDecoder().decode(key.get().publicKey()),
              key.get().signCount(),
              true);
      return mfa.advancePasskey(key.get().id(), key.get().signCount(), count, now);
    } catch (WebAuthn.Refused | IllegalArgumentException e) {
      return false;
    }
  }

  // ── setting factors up ──────────────────────────────────────────────────────

  /** A new authenticator secret, pending until a code from it is confirmed. */
  public MfaDtos.TotpEnrolment beginTotp(UUID userId, String email) {
    if (mfa.totp(userId).filter(Mfa.Totp::active).isPresent()) {
      throw ApiException.conflict(
          "MFA_TOTP_ALREADY_ACTIVE", "An authenticator app is already set up");
    }
    byte[] secret = Totp.newSecret();
    mfa.putPendingTotp(userId, sealer.seal(secret), Instant.now());
    return new MfaDtos.TotpEnrolment(
        Totp.base32(secret), Totp.otpauthUri(config.mfaIssuer(), email, secret));
  }

  /**
   * Confirms the app shows the right code, which is what makes the factor count.
   *
   * @return new recovery codes when this is the login's first factor, else none
   */
  public List<String> confirmTotp(UUID userId, String code) {
    Mfa.Totp totp =
        mfa.totp(userId)
            .orElseThrow(
                () -> ApiException.badRequest("MFA_TOTP_NOT_STARTED", "Start the set-up first"));
    if (totp.active()) {
      throw ApiException.conflict(
          "MFA_TOTP_ALREADY_ACTIVE", "An authenticator app is already set up");
    }
    Instant now = Instant.now();
    long step = Totp.verify(sealer.open(totp.secretSealed()), code, now, totp.lastUsedStep());
    if (step < 0 || !mfa.useTotpStep(userId, step, now)) {
      throw ApiException.badRequest("MFA_CODE_INVALID", "That code did not match");
    }
    users.audit(null, userId, "MFA_TOTP_ENROLLED", null);
    return recoveryCodesIfFirst(userId, now);
  }

  /**
   * Reads an authenticator secret given from outside (the bootstrap administrator's), refusing one
   * that is not base 32 or is shorter than 128 bits. Called before the account exists, so a bad
   * secret leaves no account behind it.
   */
  public byte[] parseSecret(String base32Secret) {
    byte[] secret;
    try {
      secret = Totp.fromBase32(base32Secret);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "MFA_SECRET_INVALID", "The authenticator secret is not base 32", List.of(), e);
    }
    if (secret.length < 16) {
      throw ApiException.badRequest(
          "MFA_SECRET_INVALID", "The authenticator secret is shorter than 128 bits");
    }
    return secret;
  }

  /** The bootstrap administrator's authenticator, given with the account so it never lacks one. */
  public void provisionTotp(UUID userId, byte[] secret) {
    mfa.putActiveTotp(userId, sealer.seal(secret), Instant.now());
    users.audit(null, userId, "MFA_TOTP_PROVISIONED", null);
  }

  /** New recovery codes when the login has none — its first factor — else an empty list. */
  private List<String> recoveryCodesIfFirst(UUID userId, Instant now) {
    if (mfa.recoveryCodesLeft(userId) > 0) return List.of();
    return newRecoveryCodes(userId, now);
  }

  private List<String> newRecoveryCodes(UUID userId, Instant now) {
    List<String> codes = RecoveryCodes.generate();
    mfa.replaceRecoveryCodes(userId, codes.stream().map(RecoveryCodes::hash).toList(), now);
    return codes;
  }

  /** A fresh set of recovery codes; the old ones stop working. Asks for the password. */
  public List<String> regenerateRecoveryCodes(User user, String password) {
    requirePassword(user, password);
    if (methods(user.id()).isEmpty()) {
      throw ApiException.badRequest(
          "MFA_NOT_ENROLLED", "Recovery codes go with a second factor: set one up first");
    }
    users.audit(null, user.id(), "MFA_RECOVERY_CODES_REGENERATED", null);
    return newRecoveryCodes(user.id(), Instant.now());
  }

  /** What {@code navigator.credentials.create} needs to make a passkey for this login. */
  public MfaDtos.PasskeyCreationOptions beginPasskey(UUID userId, String email) {
    String token = Tokens.newOpaqueToken();
    String challenge = newChallenge();
    Instant now = Instant.now();
    mfa.createChallenge(
        userId,
        Mfa.KIND_PASSKEY_REGISTRATION,
        Tokens.hash(token),
        challenge,
        now,
        now.plusSeconds(config.mfaChallengeTtlSeconds()));
    ByteBuffer handle = ByteBuffer.allocate(16);
    handle.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits());
    return new MfaDtos.PasskeyCreationOptions(
        token,
        challenge,
        config.mfaRpId(),
        config.mfaIssuer(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(handle.array()),
        email,
        WebAuthn.algorithms(),
        mfa.passkeys(userId).stream().map(Mfa.Passkey::credentialId).toList(),
        "required",
        "none",
        PASSKEY_TIMEOUT_MILLIS);
  }

  /**
   * Keeps a passkey the browser has just made.
   *
   * @return new recovery codes when this is the login's first factor, else none
   */
  public List<String> finishPasskey(UUID userId, MfaDtos.PasskeyRegistration req) {
    Instant now = Instant.now();
    Mfa.Challenge open =
        mfa.openChallenge(Tokens.hash(req.registrationToken()), Mfa.KIND_PASSKEY_REGISTRATION, now)
            .filter(c -> c.userId().equals(userId))
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "MFA_CHALLENGE_EXPIRED", "Start the passkey set-up again"));
    // One try per challenge: a registration that fails starts over with a new one.
    if (!mfa.consume(open.id(), now)) {
      throw ApiException.badRequest("MFA_CHALLENGE_EXPIRED", "Start the passkey set-up again");
    }
    WebAuthn.Registered made;
    try {
      made =
          WebAuthn.registration(
              url(req.clientDataJson()),
              url(req.attestationObject()),
              url(open.webauthnChallenge()),
              relyingParty(),
              true);
    } catch (WebAuthn.Refused | IllegalArgumentException e) {
      throw new ApiException(
          400, "MFA_PASSKEY_INVALID", "That passkey could not be verified", List.of(), e);
    }
    String credentialId =
        Base64.getUrlEncoder().withoutPadding().encodeToString(made.credentialId());
    mfa.insertPasskey(
            userId,
            credentialId,
            Base64.getEncoder().encodeToString(made.publicKeyCose()),
            made.signCount(),
            req.name().trim(),
            made.userVerified(),
            now)
        .orElseThrow(
            () ->
                ApiException.conflict(
                    "MFA_PASSKEY_ALREADY_REGISTERED", "That passkey is already registered"));
    users.audit(null, userId, "MFA_PASSKEY_ADDED", req.name().trim());
    return recoveryCodesIfFirst(userId, now);
  }

  // ── taking factors away ─────────────────────────────────────────────────────

  /** Removes the authenticator app. Asks for the password; refused if it would break the rule. */
  public void removeTotp(User user, Set<String> roles, String password) {
    requirePassword(user, password);
    boolean lastFactor = mfa.passkeys(user.id()).isEmpty();
    refuseIfRequired(user, roles, lastFactor);
    mfa.deleteTotp(user.id());
    if (lastFactor) mfa.replaceRecoveryCodes(user.id(), List.of(), Instant.now());
    users.audit(user.tenantId(), user.id(), "MFA_FACTOR_REMOVED", METHOD_TOTP);
  }

  /** Removes one passkey. Asks for the password; refused if it would break the rule. */
  public void removePasskey(User user, Set<String> roles, UUID passkeyId, String password) {
    requirePassword(user, password);
    boolean lastFactor =
        mfa.passkeys(user.id()).size() <= 1
            && mfa.totp(user.id()).filter(Mfa.Totp::active).isEmpty();
    refuseIfRequired(user, roles, lastFactor);
    if (!mfa.deletePasskey(user.id(), passkeyId)) {
      throw ApiException.notFound("MFA_PASSKEY_NOT_FOUND", "No such passkey");
    }
    if (lastFactor) mfa.replaceRecoveryCodes(user.id(), List.of(), Instant.now());
    users.audit(user.tenantId(), user.id(), "MFA_FACTOR_REMOVED", METHOD_PASSKEY);
  }

  private void refuseIfRequired(User user, Set<String> roles, boolean lastFactor) {
    if (lastFactor && required(user.tenantId(), roles)) {
      throw ApiException.conflict(
          "MFA_REQUIRED_BY_POLICY",
          "A second factor is required of this login: set another up before removing the last");
    }
  }

  private void requirePassword(User user, String password) {
    if (!passwords.verify(user.passwordHash(), password)) {
      users.audit(user.tenantId(), user.id(), "MFA_STEP_UP_FAILED", null);
      throw ApiException.unauthorized("INVALID_CREDENTIALS", "The password did not match");
    }
  }

  /**
   * The lost-phone reset: an owner clears a member of staff's factors, the platform administrator
   * anybody's. Never one's own — that is what the password and the recovery codes are for — and it
   * ends the person's sessions, so the next thing they do is sign in and, where the rule says so,
   * set a factor up again.
   */
  public void resetFor(UUID actorId, UUID actorTenantId, Set<String> actorRoles, UUID targetId) {
    if (actorId.equals(targetId)) {
      throw ApiException.badRequest(
          "MFA_RESET_SELF", "Use your password or a recovery code for your own second factor");
    }
    User target =
        users
            .findById(targetId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No such user"));
    boolean platform = actorRoles.contains(PLATFORM_ADMIN);
    // Another business's staff are not found rather than forbidden: their existence is not ours
    // to confirm.
    if (!platform && (actorTenantId == null || !actorTenantId.equals(target.tenantId()))) {
      throw ApiException.notFound("USER_NOT_FOUND", "No such user");
    }
    mfa.clearFactors(targetId);
    refreshTokens.revokeAllForUser(targetId);
    users.audit(target.tenantId(), targetId, "MFA_RESET_BY_ADMIN", actorId.toString());
  }

  // ── the business's rule ─────────────────────────────────────────────────────

  public MfaDtos.MfaPolicy policy(UUID tenantId) {
    return new MfaDtos.MfaPolicy(mfa.requiredTiers(tenantId).stream().sorted().toList());
  }

  public MfaDtos.MfaPolicy putPolicy(UUID tenantId, UUID by, List<String> tiers) {
    Set<String> wanted = Set.copyOf(tiers);
    if (!TIERS.containsAll(wanted)) {
      throw ApiException.badRequest(
          "MFA_POLICY_TIER_UNKNOWN", "Tiers are OWNER, MANAGER, STOREKEEPER and CASHIER");
    }
    mfa.putPolicy(tenantId, wanted, by, Instant.now());
    users.audit(
        tenantId, by, "MFA_POLICY_CHANGED", String.join(",", wanted.stream().sorted().toList()));
    return policy(tenantId);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private WebAuthn.RelyingParty relyingParty() {
    return new WebAuthn.RelyingParty(config.mfaRpId(), config.mfaOrigins());
  }

  private static String newChallenge() {
    byte[] challenge = new byte[CHALLENGE_BYTES];
    RANDOM.nextBytes(challenge);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
  }

  private static byte[] url(String base64url) {
    return Base64.getUrlDecoder().decode(base64url);
  }
}
