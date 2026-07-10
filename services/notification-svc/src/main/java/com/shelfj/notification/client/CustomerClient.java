package com.shelfj.notification.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.notification.config.ServiceConfig;
import com.shelfj.web.HttpHeaders;
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
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a customer's email from customer-svc so notification-svc can address an order
 * confirmation (golden rule #1/#4). Best-effort: any failure returns empty and the notification is
 * simply skipped — a missing confirmation email must never block or crash the consumer. {@code GET
 * /customers/{id}} is a read, so customer-svc's mutating-only {@code AdminAuthorizationFilter}
 * doesn't require a role — only the tenant header.
 */
@ApplicationScoped
public class CustomerClient {

  private static final Logger LOG = System.getLogger(CustomerClient.class.getName());
  private static final String CUSTOMER_SERVICE = "customer-svc";

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

  public Optional<String> emailOf(UUID tenantId, UUID customerId) {
    try {
      ServiceInstance instance = registry.resolve(CUSTOMER_SERVICE).orElse(null);
      if (instance == null) {
        return Optional.empty();
      }
      try (HttpClientResponse res =
          webClient
              .get(instance.baseUri() + "/customers/" + customerId)
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .request()) {
        if (res.status().code() != 200) {
          return Optional.empty();
        }
        try (JsonReader reader = Json.createReader(new StringReader(res.as(String.class)))) {
          JsonObject data = reader.readObject().getJsonObject("data");
          if (data == null || data.isNull("email")) {
            return Optional.empty();
          }
          String email = data.getString("email", null);
          return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING, "customer email lookup failed for {0}: {1}", customerId, e.getMessage());
      return Optional.empty();
    }
  }
}
