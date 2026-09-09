package com.shelfj.order.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.web.ApiException;
import com.shelfj.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for pricing-svc's {@code POST /prices/resolve} (golden rule #1: foreign data comes
 * from the owning service, never its tables; rule #4: the instance is resolved via Consul, not a
 * hardcoded host:port).
 *
 * <p>Fail-closed: when price enforcement is on and pricing-svc is unreachable or has no price for
 * the variant, order placement is rejected — a sale at a client-chosen price is worse than a lost
 * sale.
 */
@ApplicationScoped
public class PricingClient {

  private static final System.Logger LOG = System.getLogger(PricingClient.class.getName());

  /**
   * Stamped on the two calls added with the promotion engine, for the reason SJ-D13 established: an
   * internal call carrying no principal is one routing mistake away from being an impersonation, so
   * it names a role rather than relying on a bypass.
   *
   * <p>Deliberately <em>not</em> added to the filter's open-mutation allowlist the way {@code
   * /prices/resolve*} was. Quoting only reveals a shopper's own basket, but {@code
   * /prices/redemptions} spends a coupon — and an endpoint that spends money should not be open
   * merely because the one beside it could be.
   */
  private static final String INTERNAL_ROLE = "CASHIER";

  private static final String PRICING_SERVICE = "pricing-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  /**
   * The pricing-svc-resolved figures for one order line: {@code unitPrice} already has any active
   * promotion discount applied, and {@code vatAmount} is the per-unit tax pricing-svc computed from
   * the variant's VAT category. Both are authoritative — never overridden by client input when
   * price enforcement is on.
   */
  public record ResolvedLine(BigDecimal unitPrice, BigDecimal vatAmount) {}

  /**
   * Returns the effective unit price and VAT for one order line, as decided by pricing-svc (price
   * list + active promotions + VAT rate). Throws 422 when no price is configured, 503 when
   * pricing-svc cannot be reached.
   *
   * <p>{@code @Retry}: up to 2 retries on transient network errors; aborts immediately on {@link
   * ApiException} (a valid error response from pricing-svc — retrying a 404 is pointless).
   * {@code @CircuitBreaker}: trips after 60 % failures in a 5-request window; stays open for 5 s so
   * a dead pricing-svc doesn't cause every checkout to block for 5 s before failing. {@link
   * CircuitBreakerOpenException} is caught below and mapped to 503.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public ResolvedLine resolveLine(
      UUID tenantId, UUID variantId, UUID storeId, String channel, BigDecimal qty) {
    ServiceInstance instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonObjectBuilder payload = Json.createObjectBuilder().add("variantId", variantId.toString());
    if (storeId != null) payload.add("storeId", storeId.toString());
    if (channel != null) payload.add("channel", channel);
    if (qty != null) payload.add("qty", qty);

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/prices/resolve")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload.build().toString())) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for variant " + variantId);
      }
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
        BigDecimal vatAmount =
            data.containsKey("vatAmount") && !data.isNull("vatAmount")
                ? data.getJsonNumber("vatAmount").bigDecimalValue()
                : BigDecimal.ZERO;
        return new ResolvedLine(unitPrice, vatAmount);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  /** One line to resolve in a {@link #resolveLines} batch call. */
  public record LineRequest(UUID variantId, BigDecimal qty) {}

  /**
   * One promotion that pricing-svc applied to a basket.
   *
   * @param variantId the line it came off, or null for a whole-basket promotion
   */
  public record AppliedPromotion(
      UUID promotionId, String name, UUID variantId, BigDecimal amount) {}

  /**
   * A basket priced by pricing-svc: net line prices with line-level promotions already in them,
   * plus the whole-basket discount that belongs to no single line.
   *
   * @param lines per-line net unit price and VAT, in request order
   * @param basketDiscount what the basket-level rules took off the order as a whole
   * @param applied every promotion that took money off, for the receipt and the redemption ledger
   * @param rejectedCoupons codes the customer presented that did not apply, and why
   */
  public record QuotedBasket(
      List<ResolvedLine> lines,
      BigDecimal basketDiscount,
      List<AppliedPromotion> applied,
      java.util.Map<String, String> rejectedCoupons) {
    public QuotedBasket {
      lines = List.copyOf(lines);
      applied = List.copyOf(applied);
      rejectedCoupons = java.util.Map.copyOf(rejectedCoupons);
    }
  }

  /**
   * Prices a whole basket through {@code POST /prices/quote}.
   *
   * <p>Distinct from {@link #resolveLines}, which prices each line independently: a spend
   * threshold, a basket percentage and a buy-one-get-one all need the order total to exist before
   * they mean anything, and the per-line form gave them nothing to be about. Checkout uses this;
   * {@code resolveLines} stays for callers that genuinely are pricing one line at a time.
   *
   * <p>Fails closed exactly as {@code resolveLines} does — an unpriceable line is 422, an
   * unreachable pricing-svc is 503 — because a checkout that silently prices at zero is worse than
   * one that refuses.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public QuotedBasket quoteBasket(
      UUID tenantId,
      List<LineRequest> lines,
      UUID storeId,
      String channel,
      UUID customerId,
      List<String> couponCodes) {
    if (lines.isEmpty())
      return new QuotedBasket(List.of(), BigDecimal.ZERO, List.of(), java.util.Map.of());
    ServiceInstance instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonArrayBuilder linesArray = Json.createArrayBuilder();
    for (LineRequest l : lines) {
      JsonObjectBuilder lineObj =
          Json.createObjectBuilder().add("variantId", l.variantId().toString());
      if (l.qty() != null) lineObj.add("qty", l.qty());
      linesArray.add(lineObj);
    }
    JsonObjectBuilder body = Json.createObjectBuilder().add("lines", linesArray);
    if (storeId != null) body.add("storeId", storeId.toString());
    if (channel != null) body.add("channel", channel);
    if (customerId != null) body.add("customerId", customerId.toString());
    if (couponCodes != null && !couponCodes.isEmpty()) {
      JsonArrayBuilder codes = Json.createArrayBuilder();
      for (String c : couponCodes) if (c != null && !c.isBlank()) codes.add(c);
      body.add("couponCodes", codes);
    }

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/prices/quote")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body.build().toString())) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for one or more order lines");
      }
      if (status != 200) throw unavailable("pricing-svc returned HTTP " + status, null);
      try (JsonReader reader = Json.createReader(new StringReader(res.as(String.class)))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        return parseQuote(data);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc call failed", e);
    }
  }

  /**
   * Parsing lives in its own method so it can be tested without a web client behind service
   * discovery and a circuit breaker — which is exactly why SJ-D14 went unnoticed: the only two
   * sites in the codebase that parsed a DTO inline were the two that were wrong.
   *
   * <p>Every optional field is read with the {@code containsKey && !isNull} form rather than {@code
   * isNull} alone, because JSON-B omits a null field entirely and {@code isNull} throws on an
   * absent key. That is SJ-D14 exactly, and it 503'd every guest checkout for months.
   */
  static QuotedBasket parseQuote(JsonObject data) {
    JsonArray lineArray = data.getJsonArray("lines");
    List<ResolvedLine> lines = new ArrayList<>(lineArray.size());
    for (var l : lineArray) {
      JsonObject o = l.asJsonObject();
      BigDecimal qty = num(o, "qty", BigDecimal.ONE);
      BigDecimal net = num(o, "netTotal", BigDecimal.ZERO);
      // The engine reports money per line; the order stores a unit price, so divide back out.
      BigDecimal unit =
          qty.signum() == 0 ? BigDecimal.ZERO : net.divide(qty, 2, java.math.RoundingMode.HALF_UP);
      lines.add(new ResolvedLine(unit, num(o, "vatAmount", BigDecimal.ZERO)));
    }

    List<AppliedPromotion> applied = new ArrayList<>();
    if (data.containsKey("appliedPromotions") && !data.isNull("appliedPromotions")) {
      for (var a : data.getJsonArray("appliedPromotions")) {
        JsonObject o = a.asJsonObject();
        applied.add(
            new AppliedPromotion(
                UUID.fromString(o.getString("promotionId")),
                o.containsKey("name") && !o.isNull("name") ? o.getString("name") : "",
                o.containsKey("variantId") && !o.isNull("variantId")
                    ? UUID.fromString(o.getString("variantId"))
                    : null,
                num(o, "amount", BigDecimal.ZERO)));
      }
    }

    java.util.Map<String, String> rejected = new java.util.LinkedHashMap<>();
    if (data.containsKey("rejectedCoupons") && !data.isNull("rejectedCoupons")) {
      JsonObject r = data.getJsonObject("rejectedCoupons");
      for (String k : r.keySet()) rejected.put(k, r.getString(k));
    }

    return new QuotedBasket(lines, num(data, "basketDiscount", BigDecimal.ZERO), applied, rejected);
  }

  /**
   * Tells pricing-svc that an order used these promotions, so their usage caps are spent.
   *
   * <p><b>Quietly</b>, and that is the important word. The order is already placed and, at a till,
   * already paid for. A customer must not lose their order because a redemption counter could not
   * be written — so this logs and returns rather than throwing. The write is idempotent on the
   * order, so the worst case of a lost call is an uncounted redemption, which is recoverable;
   * failing the checkout is not.
   */
  public void recordRedemptionsQuietly(
      UUID tenantId,
      UUID orderId,
      UUID customerId,
      List<AppliedPromotion> applied,
      String currency) {
    try {
      ServiceInstance instance = registry.resolve(PRICING_SERVICE).orElse(null);
      if (instance == null) {
        LOG.log(
            System.Logger.Level.WARNING,
            "No pricing-svc instance to record promotion redemptions for order {0}",
            orderId);
        return;
      }
      JsonArrayBuilder arr = Json.createArrayBuilder();
      for (AppliedPromotion a : applied) {
        JsonObjectBuilder o =
            Json.createObjectBuilder()
                .add("promotionId", a.promotionId().toString())
                .add("name", a.name() == null ? "" : a.name())
                .add("amount", a.amount());
        if (a.variantId() != null) o.add("variantId", a.variantId().toString());
        arr.add(o);
      }
      JsonObjectBuilder body =
          Json.createObjectBuilder()
              .add("orderId", orderId.toString())
              .add("currency", currency == null ? "GBP" : currency)
              .add("appliedPromotions", arr);
      if (customerId != null) body.add("customerId", customerId.toString());

      try (HttpClientResponse res =
          webClient
              .post(instance.baseUri() + "/prices/redemptions")
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
              .header(HeaderNames.CONTENT_TYPE, "application/json")
              .submit(body.build().toString())) {
        int status = res.status().code();
        if (status != 200 && status != 201) {
          LOG.log(
              System.Logger.Level.WARNING,
              "pricing-svc returned HTTP {0} recording redemptions for order {1}",
              status,
              orderId);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Could not record promotion redemptions for order " + orderId,
          e);
    }
  }

  private static BigDecimal num(JsonObject o, String key, BigDecimal fallback) {
    return o.containsKey(key) && !o.isNull(key) ? o.getJsonNumber(key).bigDecimalValue() : fallback;
  }

  /**
   * Batch form of {@link #resolveLine} — resolves every line of an order in one HTTP call instead
   * of one call per line, removing the per-line round trip (and circuit-breaker/retry overhead)
   * that checkout used to pay once per item. Results are returned in the same order as {@code
   * lines}. Same fail-closed behavior as the single-line form: any line that can't be priced fails
   * the whole call (placeOrder never persisted a partially-priced order before this change either).
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public List<ResolvedLine> resolveLines(
      UUID tenantId, List<LineRequest> lines, UUID storeId, String channel) {
    if (lines.isEmpty()) return List.of();
    ServiceInstance instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonArrayBuilder linesArray = Json.createArrayBuilder();
    for (LineRequest l : lines) {
      JsonObjectBuilder lineObj =
          Json.createObjectBuilder().add("variantId", l.variantId().toString());
      if (storeId != null) lineObj.add("storeId", storeId.toString());
      if (channel != null) lineObj.add("channel", channel);
      if (l.qty() != null) lineObj.add("qty", l.qty());
      linesArray.add(lineObj);
    }
    String payload = Json.createObjectBuilder().add("lines", linesArray).build().toString();

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/prices/resolve-batch")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for one or more order lines");
      }
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray results = reader.readObject().getJsonObject("data").getJsonArray("results");
        List<ResolvedLine> resolved = new ArrayList<>(results.size());
        for (var r : results) {
          JsonObject data = r.asJsonObject();
          BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
          BigDecimal vatAmount =
              data.containsKey("vatAmount") && !data.isNull("vatAmount")
                  ? data.getJsonNumber("vatAmount").bigDecimalValue()
                  : BigDecimal.ZERO;
          resolved.add(new ResolvedLine(unitPrice, vatAmount));
        }
        return resolved;
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "ORDER_PRICING_UNAVAILABLE", message, List.of(), cause);
  }
}
