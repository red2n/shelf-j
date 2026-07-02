package com.shelfj.product.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.product.config.ServiceConfig;
import com.shelfj.web.ApiException;
import com.shelfj.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calls pricing-svc to (a) ensure a default ALL-channel price list exists, then (b) batch-upsert
 * selling prices for all catalogue-import rows in one round-trip. Consul-resolved (golden rule #4).
 */
@ApplicationScoped
public class PricingClient {

  private static final String PRICING_SERVICE = "pricing-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofMinutes(5))
            .build();
  }

  public record PriceItem(String variantId, BigDecimal price) {}

  public record BatchResult(int upserted, List<String> errors) {
    public BatchResult {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Finds (or creates) a tenant's default ALL-channel price list, then batch-upserts selling
   * prices. Returns how many were upserted and any per-row error strings.
   */
  public BatchResult batchSetPrices(
      UUID tenantId, String currency, String rolesHeader, List<PriceItem> items) {
    if (items.isEmpty()) return new BatchResult(0, List.of());

    var instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "PRICING_UNAVAILABLE",
                        "no healthy pricing-svc instance in discovery",
                        List.of(),
                        null));

    String priceListId =
        resolveDefaultPriceList(tenantId, currency, instance.baseUri(), rolesHeader);

    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (var item : items) {
      arr.add(
          Json.createObjectBuilder()
              .add("variantId", item.variantId())
              .add("price", item.price())
              .add("minQty", 1));
    }
    String body = Json.createObjectBuilder().add("items", arr).build().toString();

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/price-lists/" + priceListId + "/items/batch")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), rolesHeader)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body)) {
      String resp = res.as(String.class);
      if (res.status().code() >= 500) {
        return new BatchResult(0, List.of("pricing-svc error HTTP " + res.status().code()));
      }
      try (JsonReader reader = Json.createReader(new StringReader(resp))) {
        var data = reader.readObject().getJsonObject("data");
        int upserted = data.getInt("upserted", 0);
        var errs = new ArrayList<String>();
        var errArr = data.getJsonArray("errors");
        if (errArr != null) {
          for (int i = 0; i < errArr.size(); i++) {
            errs.add(errArr.getString(i));
          }
        }
        return new BatchResult(upserted, errs);
      }
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      return new BatchResult(0, List.of("pricing-svc unreachable: " + e.getMessage()));
    }
  }

  /** Returns an existing ALL-channel price list id, or creates one. */
  private String resolveDefaultPriceList(
      UUID tenantId, String currency, String baseUri, String rolesHeader) {
    try (HttpClientResponse res =
        webClient
            .get(baseUri + "/price-lists")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), rolesHeader)
            .request()) {
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        var arr = reader.readObject().getJsonArray("data");
        if (arr != null) {
          for (int i = 0; i < arr.size(); i++) {
            var pl = arr.getJsonObject(i);
            boolean active = pl.getBoolean("active", true);
            String ch = pl.getString("channel", "ALL");
            if (active && ("ALL".equals(ch) || ch == null)) {
              return pl.getString("id");
            }
          }
          // Fallback: any list
          if (!arr.isEmpty()) return arr.getJsonObject(0).getString("id");
        }
      }
    } catch (RuntimeException ignored) {
    }

    // None found — create the default.
    String cur = (currency != null && !currency.isBlank()) ? currency : "GBP";
    String createBody =
        Json.createObjectBuilder()
            .add("name", "Default")
            .add("channel", "ALL")
            .add("currency", cur)
            .add("effectiveFrom", Instant.now().toString())
            .build()
            .toString();
    try (HttpClientResponse res =
        webClient
            .post(baseUri + "/price-lists")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), rolesHeader)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(createBody)) {
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        return reader.readObject().getJsonObject("data").getString("id");
      }
    } catch (RuntimeException e) {
      throw new ApiException(
          503,
          "PRICING_UNAVAILABLE",
          "could not create default price list: " + e.getMessage(),
          List.of(),
          e);
    }
  }
}
