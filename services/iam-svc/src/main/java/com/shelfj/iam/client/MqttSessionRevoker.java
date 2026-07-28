package com.shelfj.iam.client;

import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * On logout, forces notification-svc's MQTT device-push channel to drop the user's live
 * connection instead of leaving it receiving alerts until the JWT naturally expires (EMQX's JWT
 * auth is only checked at CONNECT time, not per message — see docs/ARCHITECTURE.md's
 * notification-svc section). Best-effort: any failure (broker unreachable, feature not
 * configured, the user never had a live connection) is swallowed — a logout must never fail
 * because of this side effect.
 *
 * <p>The client id it kicks, {@code mqtt-<tenantId>-<userId>}, must match exactly what the
 * frontend's live-push connection uses — see frontends/shelf-app's
 * live_alerts_provider.dart. NOT independently verified against a running broker (no Docker in
 * the environment that authored this); MqttSessionRevokerIT (Testcontainers) is the actual
 * verification.
 */
@ApplicationScoped
public class MqttSessionRevoker {

  private static final Logger LOG = System.getLogger(MqttSessionRevoker.class.getName());

  @Inject
  @ConfigProperty(name = "shelfj.mqtt.admin-api-url", defaultValue = "http://localhost:18083")
  String apiUrl;

  // Optional: an unset key/secret means the feature is off (e.g. shelfj.notification.channel
  // isn't mqtt anywhere) — revoke() is then a no-op rather than a failed injection.
  @Inject
  @ConfigProperty(name = "shelfj.mqtt.admin-api-key")
  Optional<String> apiKey;

  @Inject
  @ConfigProperty(name = "shelfj.mqtt.admin-api-secret")
  Optional<String> apiSecret;

  private WebClient webClient;

  @PostConstruct
  void init() {
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .build();
  }

  public void revoke(UUID tenantId, UUID userId) {
    if (apiKey.isEmpty() || apiSecret.isEmpty()) {
      return;
    }
    String clientId = "mqtt-" + tenantId + "-" + userId;
    try {
      String basic =
          Base64.getEncoder()
              .encodeToString(
                  (apiKey.get() + ":" + apiSecret.get()).getBytes(StandardCharsets.UTF_8));
      try (HttpClientResponse res =
          webClient
              .delete(apiUrl + "/api/v5/clients/" + clientId)
              .header(HeaderNames.AUTHORIZATION, "Basic " + basic)
              .request()) {
        // 204 = kicked, 404 = no live session for this user (never connected, or on a different
        // device) — both are fine, there's nothing further to do either way.
        if (res.status().code() >= 500) {
          LOG.log(Level.DEBUG, "MQTT session kick for {0} returned {1}", clientId, res.status());
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.DEBUG, "MQTT session kick skipped for {0}: {1}", clientId, e.getMessage());
    }
  }
}
