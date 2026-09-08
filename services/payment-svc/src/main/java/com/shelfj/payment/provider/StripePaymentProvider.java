package com.shelfj.payment.provider;

import com.shelfj.payment.domain.Domain.PaymentIntent;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stripe, over its REST API.
 *
 * <p>Uses PaymentIntents with {@code capture_method=manual}, which is what gives the auth/capture
 * split: checkout authorises, fulfilment captures, and a cancelled order releases a hold instead of
 * owing a refund.
 *
 * <p>No card details pass through this service. The intent is created server-side and the customer
 * completes it against Stripe directly, so this code never sees a PAN and payment-svc stays out of
 * PCI-DSS scope beyond SAQ-A.
 */
@ApplicationScoped
public class StripePaymentProvider implements PaymentProvider {

  /** Stripe rejects a signature older than this; so do we, to bound replay. */
  private static final long TOLERANCE_SECONDS = 300;

  @Inject
  @ConfigProperty(name = "shelfj.payment.stripe.api-base", defaultValue = "https://api.stripe.com")
  String apiBase;

  // Optional, not defaultValue = "": MicroProfile Config treats an empty default as no default at
  // all and fails deployment when the key is absent, which is every environment that has not
  // configured Stripe — including the tests.
  @Inject
  @ConfigProperty(name = "shelfj.payment.stripe.secret-key")
  Optional<String> secretKeyConfig;

  @Inject
  @ConfigProperty(name = "shelfj.payment.stripe.webhook-secret")
  Optional<String> webhookSecretConfig;

  /** Resolved from config at startup; package-private so tests can set them directly. */
  String secretKey = "";

  String webhookSecret = "";

  private WebClient webClient;

  @PostConstruct
  void init() {
    secretKey = secretKeyConfig.orElse("");
    webhookSecret = webhookSecretConfig.orElse("");
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15))
            .build();
  }

  @Override
  public String name() {
    return PaymentIntent.PROVIDER_STRIPE;
  }

  @Override
  public String signatureHeaderName() {
    return "Stripe-Signature";
  }

  @Override
  public Authorization authorize(AuthorizeRequest request) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("amount", String.valueOf(minorUnits(request.amount(), request.currency())));
    form.put("currency", request.currency().toLowerCase(Locale.ROOT));
    // The whole point: hold the funds now, take them at fulfilment.
    form.put("capture_method", "manual");
    form.put("automatic_payment_methods[enabled]", "true");
    // Carried back on every webhook, so an event can be traced to our own record even if the
    // provider reference is somehow lost on our side.
    form.put("metadata[intentId]", request.intentId().toString());
    form.put("metadata[orderId]", request.orderId().toString());
    form.put("metadata[tenantId]", request.tenantId().toString());
    if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
      form.put("return_url", request.returnUrl());
    }

    JsonObject body = post("/v1/payment_intents", form, request.idempotencyKey());
    return new Authorization(
        body.getString("id"), statusFrom(body.getString("status", "")), nextActionUrl(body));
  }

  @Override
  public Capture capture(String providerRef, BigDecimal amount, String idempotencyKey) {
    JsonObject body =
        post("/v1/payment_intents/" + providerRef + "/capture", Map.of(), idempotencyKey);
    String currency = body.getString("currency", "gbp").toUpperCase(Locale.ROOT);
    long captured =
        body.containsKey("amount_received")
            ? body.getJsonNumber("amount_received").longValue()
            : 0L;
    return new Capture(providerRef, majorUnits(captured, currency), providerRef);
  }

  @Override
  public void cancel(String providerRef, String idempotencyKey) {
    post("/v1/payment_intents/" + providerRef + "/cancel", Map.of(), idempotencyKey);
  }

  /**
   * Verifies Stripe's {@code Stripe-Signature} header and parses the event.
   *
   * <p>Stripe signs {@code "<timestamp>.<raw body>"} with HMAC-SHA256 under the endpoint's webhook
   * secret. Three things matter and each is a way this goes wrong:
   *
   * <ul>
   *   <li>the signature covers the <em>raw</em> bytes, so the body must not be parsed and
   *       re-serialised first — key order and whitespace would change and nothing would ever
   *       verify;
   *   <li>the comparison must be constant-time, or the check leaks the expected signature a byte at
   *       a time to anyone willing to measure;
   *   <li>the timestamp must be inside a tolerance, or a captured delivery can be replayed forever.
   * </ul>
   */
  @Override
  public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      // Refusing is the only safe answer: with no secret there is nothing to verify against, and
      // accepting would mean anyone could mark any order paid.
      throw new ProviderException("no Stripe webhook secret is configured", false, null);
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new ProviderException("missing Stripe-Signature header", false, null);
    }

    String timestamp = null;
    String provided = null;
    for (String part : signatureHeader.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length != 2) {
        continue;
      }
      if ("t".equals(kv[0])) {
        timestamp = kv[1];
      } else if ("v1".equals(kv[0])) {
        provided = kv[1];
      }
    }
    if (timestamp == null || provided == null) {
      throw new ProviderException("malformed Stripe-Signature header", false, null);
    }

    long age;
    try {
      age = Math.abs(java.time.Instant.now().getEpochSecond() - Long.parseLong(timestamp));
    } catch (NumberFormatException e) {
      throw new ProviderException("malformed Stripe-Signature timestamp", false, e);
    }
    if (age > TOLERANCE_SECONDS) {
      throw new ProviderException("Stripe-Signature timestamp outside tolerance", false, null);
    }

    String payload = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
    String expected = hmacSha256Hex(webhookSecret, payload);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
      throw new ProviderException("Stripe-Signature did not verify", false, null);
    }

    return parseEvent(rawBody);
  }

  /**
   * Parses a verified Stripe event body into the provider-neutral shape.
   *
   * @param rawBody the verified bytes
   * @return the event
   */
  static WebhookEvent parseEvent(byte[] rawBody) {
    try (JsonReader reader =
        Json.createReader(new StringReader(new String(rawBody, StandardCharsets.UTF_8)))) {
      JsonObject root = reader.readObject();
      String type = root.getString("type", "");
      JsonObject intent = root.getJsonObject("data").getJsonObject("object");
      String currency = intent.getString("currency", "gbp").toUpperCase(Locale.ROOT);

      BigDecimal captured = null;
      if (intent.containsKey("amount_received") && !intent.isNull("amount_received")) {
        captured = majorUnits(intent.getJsonNumber("amount_received").longValue(), currency);
      }

      String failureCode = null;
      String failureMessage = null;
      if (intent.containsKey("last_payment_error") && !intent.isNull("last_payment_error")) {
        JsonObject err = intent.getJsonObject("last_payment_error");
        failureCode = err.getString("code", null);
        failureMessage = err.getString("message", null);
      }

      return new WebhookEvent(
          root.getString("id"),
          type,
          intent.getString("id"),
          statusForEvent(type, intent.getString("status", "")),
          captured,
          failureCode,
          failureMessage);
    } catch (RuntimeException e) {
      throw new ProviderException("could not parse Stripe event", false, e);
    }
  }

  /**
   * Maps a Stripe event to the status it implies.
   *
   * <p>Driven by the event type first and the intent status second, because the two disagree in the
   * case that matters: {@code payment_intent.amount_capturable_updated} carries status {@code
   * requires_capture}, which is Stripe's way of saying authorised.
   *
   * @param type the event type
   * @param intentStatus the intent's own status
   * @return the corresponding {@code Domain.PaymentIntent} status
   */
  static String statusForEvent(String type, String intentStatus) {
    return switch (type) {
      case "payment_intent.succeeded" -> PaymentIntent.STATUS_CAPTURED;
      case "payment_intent.payment_failed" -> PaymentIntent.STATUS_FAILED;
      case "payment_intent.canceled" -> PaymentIntent.STATUS_CANCELLED;
      case "payment_intent.amount_capturable_updated" -> PaymentIntent.STATUS_AUTHORIZED;
      default -> statusFrom(intentStatus);
    };
  }

  /**
   * @param stripeStatus a Stripe PaymentIntent status
   * @return the corresponding {@code Domain.PaymentIntent} status
   */
  static String statusFrom(String stripeStatus) {
    return switch (stripeStatus) {
      case "requires_capture" -> PaymentIntent.STATUS_AUTHORIZED;
      case "succeeded" -> PaymentIntent.STATUS_CAPTURED;
      case "canceled" -> PaymentIntent.STATUS_CANCELLED;
      default -> PaymentIntent.STATUS_REQUIRES_ACTION;
    };
  }

  private static String nextActionUrl(JsonObject body) {
    if (!body.containsKey("next_action") || body.isNull("next_action")) {
      return null;
    }
    JsonObject action = body.getJsonObject("next_action");
    if (action.containsKey("redirect_to_url") && !action.isNull("redirect_to_url")) {
      return action.getJsonObject("redirect_to_url").getString("url", null);
    }
    return null;
  }

  /**
   * Zero-decimal currencies (JPY, KRW…) are quoted in whole units; everything else in hundredths.
   * Treating them all as hundredths overcharges by 100x on those currencies.
   *
   * @param currency ISO-4217 code
   * @return the exponent to scale by
   */
  static int exponent(String currency) {
    return switch (currency.toUpperCase(Locale.ROOT)) {
      case "JPY", "KRW", "VND", "CLP", "ISK" -> 0;
      default -> 2;
    };
  }

  static long minorUnits(BigDecimal amount, String currency) {
    return amount
        .movePointRight(exponent(currency))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  static BigDecimal majorUnits(long minor, String currency) {
    return BigDecimal.valueOf(minor).movePointLeft(exponent(currency));
  }

  static String hmacSha256Hex(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.GeneralSecurityException e) {
      throw new ProviderException("could not compute webhook signature", false, e);
    }
  }

  private JsonObject post(String path, Map<String, String> form, String idempotencyKey) {
    if (secretKey == null || secretKey.isBlank()) {
      throw new ProviderException("no Stripe secret key is configured", false, null);
    }
    StringBuilder encoded = new StringBuilder();
    form.forEach(
        (k, v) -> {
          if (!encoded.isEmpty()) {
            encoded.append('&');
          }
          encoded
              .append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });

    try {
      var request =
          webClient
              .post(apiBase + path)
              .header(HeaderNames.AUTHORIZATION, "Bearer " + secretKey)
              .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
      if (idempotencyKey != null && !idempotencyKey.isBlank()) {
        // Stripe's own replay guard, so a retry of ours cannot become a second authorisation.
        request = request.header(HeaderNames.create("Idempotency-Key"), idempotencyKey);
      }
      try (HttpClientResponse res = request.submit(encoded.toString())) {
        String body = res.as(String.class);
        int status = res.status().code();
        if (status >= 500) {
          throw new ProviderException("Stripe returned HTTP " + status, true, null);
        }
        if (status >= 400) {
          throw new ProviderException("Stripe rejected the request: " + body, false, null);
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
          return reader.readObject();
        }
      }
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      // Reached Stripe or not, we cannot tell — retryable, because a timeout on the way back may
      // still have created the intent.
      throw new ProviderException("Stripe unreachable: " + e.getMessage(), true, e);
    }
  }
}
