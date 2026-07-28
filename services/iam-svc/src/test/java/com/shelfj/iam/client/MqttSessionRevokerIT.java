package com.shelfj.iam.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.auth0.jwt.JWT;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.shelfj.test.EmqxSupport;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Proves logout's MQTT kick ({@link MqttSessionRevoker}, wired via {@code AuthService.logout})
 * actually disconnects a live device session against a real EMQX broker — not just that the HTTP
 * call was made and returned 2xx. This is also the actual verification of infra/emqx-api-key.conf's
 * bootstrap-file format, which was authored without a broker available to test against.
 */
@HelidonTest
class MqttSessionRevokerIT {

  private static final PostgresSupport PG;
  private static final EmqxSupport MQTT;
  private static final String JWT_SECRET = "integration-test-secret-of-at-least-32-chars";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    MQTT = EmqxSupport.start(JWT_SECRET);
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.jwt.secret", JWT_SECRET);
    System.setProperty("shelfj.mqtt.admin-api-url", "http://" + MQTT.host() + ":" + MQTT.apiPort());
    // Must match infra/emqx-api-key.conf's literal contents — see EmqxSupport's javadoc.
    System.setProperty("shelfj.mqtt.admin-api-key", "iam-svc");
    System.setProperty("shelfj.mqtt.admin-api-secret", "iam_mqtt_admin_dev_change_me");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopInfra() {
    PG.stop();
    MQTT.stop();
  }

  private Response post(String path, String json) {
    return target.path(path).request().post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static Connection iamConnection() throws java.sql.SQLException {
    var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  /** Tiny JSON field extractor (avoids pulling a JSON lib into the test) — mirrors AuthIT. */
  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) {
      throw new AssertionError("field " + name + " not in: " + json);
    }
    int start = i + key.length();
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  @Test
  void logoutDisconnectsTheUsersLiveMqttSession() throws Exception {
    Response reg =
        post("/auth/register", "{\"email\":\"kick@example.com\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));

    UUID tenantId = UUID.randomUUID();
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "UPDATE users SET tenant_id=?, type='STAFF' WHERE lower(email)=lower(?)")) {
      ps.setObject(1, tenantId);
      ps.setString(2, "kick@example.com");
      ps.executeUpdate();
    }

    Response login =
        post("/auth/login", "{\"email\":\"kick@example.com\",\"password\":\"strongpass1\"}");
    assertThat(login.getStatus(), is(200));
    String loginBody = login.readEntity(String.class);
    String accessToken = field(loginBody, "accessToken");
    String refreshToken = field(loginBody, "refreshToken");
    assertThat(
        "sanity check: the issued token really is scoped to the tenant we just bound",
        JWT.decode(accessToken).getClaim("tenant").asString(),
        is(tenantId.toString()));

    // Simulate the Flutter admin shell's live-push connection: same clientId scheme
    // (live_alerts_provider.dart: "mqtt-{tenantId}-{userId}"), same JWT as the MQTT password.
    String userId = JWT.decode(accessToken).getSubject();
    Mqtt5BlockingClient device =
        Mqtt5Client.builder()
            .identifier("mqtt-" + tenantId + "-" + userId)
            .serverHost(MQTT.host())
            .serverPort(MQTT.port())
            .buildBlocking();
    device
        .connectWith()
        .simpleAuth()
        .username(tenantId.toString())
        .password(accessToken.getBytes(StandardCharsets.UTF_8))
        .applySimpleAuth()
        .send();
    device
        .subscribeWith()
        .topicFilter("shelfj/notifications/" + tenantId + "/#")
        .qos(MqttQos.AT_LEAST_ONCE)
        .send();
    assertThat(device.getState().isConnected(), is(true));

    Response logout = post("/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}");
    assertThat(logout.getStatus(), is(200));

    boolean disconnected = false;
    for (int i = 0; i < 100 && !disconnected; i++) {
      Thread.sleep(100);
      disconnected = !device.getState().isConnected();
    }
    assertThat(
        "the broker must disconnect the device once logout kicks its MQTT session",
        disconnected,
        is(true));
  }
}
