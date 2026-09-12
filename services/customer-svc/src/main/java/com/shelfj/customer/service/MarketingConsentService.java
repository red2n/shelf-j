package com.shelfj.customer.service;

import com.shelfj.customer.domain.Domain.Customer;
import com.shelfj.customer.domain.Domain.MarketingConsentEntry;
import com.shelfj.customer.domain.Domain.MarketingPreference;
import com.shelfj.customer.dto.Dtos.MarketingAllowanceResponse;
import com.shelfj.customer.dto.Dtos.MarketingChannelChoice;
import com.shelfj.customer.dto.Dtos.SetMarketingPreferencesRequest;
import com.shelfj.customer.repo.CustomerRepository;
import com.shelfj.ids.Ids;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Marketing consent, preferences and the unsubscribe link (PECR reg.22/23, UK GDPR art.7(1) and
 * art.21(3)).
 *
 * <p>Kept apart from {@link CustomerService} because it answers a different question. That one is
 * about who a person is to the shop; this one is about what the shop is allowed to send them, which
 * has its own law, its own evidence requirement, and its own way of being withdrawn.
 *
 * <p>The rule this service exists to enforce is that <strong>silence is not consent</strong>: a
 * channel with no preference row is refused, not permitted. Everything else follows from that.
 */
@ApplicationScoped
public class MarketingConsentService {

  /** Hard cap on the evidence trail an export or a screen reads at once. */
  private static final int CONSENT_LOG_LIMIT = 1000;

  private static final Set<String> CHANNELS =
      Set.of(
          MarketingPreference.CHANNEL_EMAIL,
          MarketingPreference.CHANNEL_SMS,
          MarketingPreference.CHANNEL_PHONE,
          MarketingPreference.CHANNEL_POST);

  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject CustomerRepository repo;

  /**
   * What this shop may currently send one person.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @return one entry per channel ever decided; a channel with no entry has no consent
   */
  public List<MarketingPreference> preferences(UUID tenantId, UUID customerId) {
    return repo.listPreferences(tenantId, customerId);
  }

  /**
   * The evidence behind those preferences (UK GDPR art.7(1)).
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @return every recorded change, newest first
   */
  public List<MarketingConsentEntry> consentLog(UUID tenantId, UUID customerId) {
    return repo.listConsentLog(tenantId, customerId, CONSENT_LOG_LIMIT);
  }

  /**
   * Records what a person has agreed to, and the evidence for it.
   *
   * <p>Channels the request does not name are left alone: a preference centre saving one switch
   * must not silently withdraw the others.
   *
   * @param tenantId owning tenant
   * @param customer the customer whose preferences these are; an erased record refuses
   * @param req the channels being changed and the wording the person was shown
   * @param source how the change reached the shop, e.g. the preference centre or a staff member
   * @param actorId the staff member acting, or {@code null} when the customer did it themselves
   * @return the preferences as they now stand
   * @throws ApiException {@code MARKETING_CHANNEL_UNKNOWN} (400) for a channel that is not one of
   *     EMAIL, SMS, PHONE, POST; {@code CUSTOMER_ANONYMIZED} (409) for an erased customer
   */
  public List<MarketingPreference> setPreferences(
      UUID tenantId,
      Customer customer,
      SetMarketingPreferencesRequest req,
      String source,
      UUID actorId) {
    if (Customer.STATUS_ANONYMIZED.equals(customer.status())) {
      throw ApiException.conflict(
          "CUSTOMER_ANONYMIZED", "Customer has been erased and can no longer be marketed to");
    }
    if (req.channels() == null || req.channels().isEmpty()) {
      throw ApiException.badRequest("MARKETING_NO_CHANNELS", "name at least one channel");
    }
    Instant now = Instant.now();
    List<MarketingConsentEntry> entries = new ArrayList<>();
    for (MarketingChannelChoice choice : req.channels()) {
      String channel = choice.channel().trim().toUpperCase(Locale.ROOT);
      if (!CHANNELS.contains(channel)) {
        throw ApiException.badRequest(
            "MARKETING_CHANNEL_UNKNOWN", "unknown marketing channel: " + choice.channel());
      }
      boolean granted = Boolean.TRUE.equals(choice.granted());
      entries.add(
          new MarketingConsentEntry(
              Ids.newId(),
              tenantId,
              customer.id(),
              channel,
              granted,
              granted ? basisOf(choice.basis()) : MarketingPreference.BASIS_NONE,
              source,
              granted ? req.notice() : null,
              actorId,
              now));
    }
    repo.recordConsent(entries);
    return repo.listPreferences(tenantId, customer.id());
  }

  private static String basisOf(String requested) {
    if (requested == null || requested.isBlank()) {
      return MarketingPreference.BASIS_CONSENT;
    }
    String basis = requested.trim().toUpperCase(Locale.ROOT);
    if (MarketingPreference.BASIS_CONSENT.equals(basis)
        || MarketingPreference.BASIS_SOFT_OPT_IN.equals(basis)) {
      return basis;
    }
    throw ApiException.badRequest(
        "MARKETING_BASIS_UNKNOWN",
        "a marketing basis is CONSENT or SOFT_OPT_IN; an opt-out records neither");
  }

  /**
   * Whether one marketing message may lawfully be sent, and the opt-out token it must carry.
   *
   * <p>notification-svc asks this before every marketing send. An unknown channel, a customer with
   * no preference, an opt-out, or an erased record all answer no — the default is refusal, because
   * a shop that cannot show consent does not have it.
   *
   * <p>A fresh token is minted per allowed send, and only its hash is stored. Per-send rather than
   * per-customer so one leaked message cannot be replayed against a link that outlives it.
   *
   * @param tenantId owning tenant
   * @param customerId the person to be contacted
   * @param channelRaw the channel the message would go out on
   * @return the decision, with the unsubscribe token when the answer is yes
   */
  public MarketingAllowanceResponse allowance(UUID tenantId, UUID customerId, String channelRaw) {
    String channel = channelRaw == null ? "" : channelRaw.trim().toUpperCase(Locale.ROOT);
    if (!CHANNELS.contains(channel)) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "unknown channel: " + channelRaw, null);
    }
    Customer customer = repo.findById(tenantId, customerId).orElse(null);
    if (customer == null || Customer.STATUS_ANONYMIZED.equals(customer.status())) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "no active customer record", null);
    }
    MarketingPreference pref = repo.findPreference(tenantId, customerId, channel).orElse(null);
    if (pref == null) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "no consent recorded for this channel", null);
    }
    if (!pref.granted()) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "the customer has opted out", null);
    }
    String token = mintToken();
    repo.storeUnsubscribeToken(tenantId, customerId, hash(token));
    return new MarketingAllowanceResponse(true, pref.basis(), null, token);
  }

  /**
   * Acts on the link in a marketing message: stops one channel, or every channel.
   *
   * <p>Unauthenticated by design — the token is the capability, and requiring a sign-in to stop
   * marketing would fail PECR reg.23's "simple means of refusing". A token that has been used
   * before still works: an objection does not expire, and a second click must not answer "invalid".
   *
   * @param token the raw token from the link
   * @param channelRaw one channel to stop, or {@code null} to stop all of them
   * @return how many channels were stopped
   * @throws ApiException {@code UNSUBSCRIBE_TOKEN_INVALID} (404) when the token is not one this
   *     shop issued
   */
  public int unsubscribe(String token, String channelRaw) {
    String tokenHash = hash(token);
    var subject =
        repo.findUnsubscribeSubject(tokenHash)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "UNSUBSCRIBE_TOKEN_INVALID", "this opt-out link is not one we issued"));
    List<String> channels;
    if (channelRaw == null || channelRaw.isBlank()) {
      channels = List.copyOf(CHANNELS);
    } else {
      String channel = channelRaw.trim().toUpperCase(Locale.ROOT);
      if (!CHANNELS.contains(channel)) {
        throw ApiException.badRequest(
            "MARKETING_CHANNEL_UNKNOWN", "unknown marketing channel: " + channelRaw);
      }
      channels = List.of(channel);
    }
    Instant now = Instant.now();
    List<MarketingConsentEntry> entries =
        channels.stream()
            .map(
                channel ->
                    new MarketingConsentEntry(
                        Ids.newId(),
                        subject.tenantId(),
                        subject.customerId(),
                        channel,
                        false,
                        MarketingPreference.BASIS_NONE,
                        MarketingConsentEntry.SOURCE_UNSUBSCRIBE_LINK,
                        null,
                        null,
                        now))
            .toList();
    int stopped = repo.recordConsent(entries);
    repo.markUnsubscribeTokenUsed(tokenHash);
    return stopped;
  }

  private static String mintToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * SHA-256, not a password hash: the token is 256 bits of randomness rather than something a
   * person chose, so there is nothing to brute-force and no reason to make verification slow.
   */
  private static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Base64.getEncoder()
          .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }
}
