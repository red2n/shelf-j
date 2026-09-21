package com.storeql.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Single sign-on (20.x, SSO over OpenID Connect): a business connecting its identity provider, and
 * a sign-in going through it. No tenant in any request: a business's own settings are its JWT's
 * tenant, and a sign-in's tenant is the one whose sign-in name was typed.
 */
public final class SsoDtos {

  private SsoDtos() {}

  // ── a business's provider ───────────────────────────────────────────────────

  @Schema(
      name = "SsoConnectionRequest",
      description =
          "A business's identity provider. The client secret is written, never read back: leave it"
              + " out to keep the one already held.")
  public record ConnectionRequest(
      @Schema(
              description =
                  "The sign-in name staff type: 3 to 63 lower-case letters, digits and hyphens,"
                      + " unique on the platform.")
          @NotBlank
          @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
          String slug,
      @Schema(
              description =
                  "The provider's issuer, exactly as its discovery document states it — e.g."
                      + " https://login.microsoftonline.com/{tenant}/v2.0.")
          @NotBlank
          @Size(max = 512)
          String issuer,
      @Schema(description = "The client id the provider gave this platform.")
          @NotBlank
          @Size(max = 255)
          String clientId,
      @Schema(description = "The client secret. Required the first time.") @Size(max = 1024)
          String clientSecret,
      @NotNull Boolean enabled,
      @Schema(
              description =
                  "Tiers a password no longer signs in: MANAGER, STOREKEEPER, CASHIER. Never OWNER —"
                      + " an owner can always sign in with a password, so a provider that breaks"
                      + " cannot lock the business out.")
          @NotNull
          List<String> requiredTiers,
      @Schema(
              description =
                  "Whether a first sign-in is matched to a login by email only when the provider"
                      + " says it verified the address. True unless given.")
          Boolean requireVerifiedEmail) {

    public ConnectionRequest {
      requiredTiers = requiredTiers == null ? null : List.copyOf(requiredTiers);
    }
  }

  @Schema(name = "SsoConnection")
  public record ConnectionView(
      String slug,
      String issuer,
      String clientId,
      @Schema(description = "Whether a client secret is held. The secret itself is never shown.")
          boolean clientSecretSet,
      boolean enabled,
      List<String> requiredTiers,
      boolean requireVerifiedEmail,
      @Schema(
              description =
                  "The redirect URI to register with the provider: where it sends staff back.")
          String callbackUrl,
      String updatedAt) {

    public ConnectionView {
      requiredTiers = List.copyOf(requiredTiers);
    }
  }

  @Schema(name = "SsoReadinessCheck")
  public record ReadinessCheck(
      @Schema(
              description =
                  "CALLBACK_CONFIGURED, CONNECTION_SAVED, SECRET_HELD, DISCOVERY_READ, KEYS_READ or"
                      + " ENABLED.")
          String code,
      boolean satisfied,
      @Schema(description = "What holds, or what to do about it.") String detail) {}

  @Schema(name = "SsoReadiness")
  public record Readiness(
      @Schema(description = "True when every check holds: staff can sign in through the provider.")
          boolean ready,
      List<ReadinessCheck> checks) {

    public Readiness {
      checks = List.copyOf(checks);
    }
  }

  @Schema(name = "SsoLinkedLogin", description = "A login and the provider's person it is.")
  public record LinkedLogin(
      String id,
      String userId,
      @Schema(description = "The login's own email.") String loginEmail,
      @Schema(description = "The provider's subject for this person.") String subject,
      @Schema(description = "The email the provider asserted at the last sign-in.")
          String providerEmail,
      String linkedAt,
      String lastLoginAt) {}

  @Schema(name = "SsoLinkedLoginPage")
  public record LinkedLoginPage(List<LinkedLogin> items, String nextCursor) {

    public LinkedLoginPage {
      items = List.copyOf(items);
    }
  }

  // ── a sign-in ───────────────────────────────────────────────────────────────

  @Schema(name = "SsoStartRequest")
  public record StartRequest(
      @Schema(description = "The business's sign-in name.") @NotBlank @Size(max = 63) String slug,
      @Schema(
              description =
                  "BASE64URL(SHA256(verifier)) of a random verifier the app keeps, and presents"
                      + " with the ticket: only the app that started a sign-in can finish it.")
          @NotBlank
          @Pattern(regexp = "[A-Za-z0-9_-]{43}")
          String codeChallenge,
      @Schema(
              description =
                  "Where the browser is sent back to, on one of the platform's app origins, with"
                      + " no fragment. The app's own origin when left out.")
          @Size(max = 512)
          String returnTo) {}

  @Schema(name = "SsoStart")
  public record Started(
      @Schema(description = "Send the browser here: the business's identity provider.")
          String authorizationUrl) {}

  @Schema(name = "SsoTokenRequest")
  public record TicketRequest(
      @Schema(description = "The sso_ticket the browser came back with.") @NotBlank @Size(max = 128)
          String ticket,
      @Schema(description = "The verifier the start's codeChallenge was made from.")
          @NotBlank
          @Size(min = 43, max = 128)
          String codeVerifier) {}
}
