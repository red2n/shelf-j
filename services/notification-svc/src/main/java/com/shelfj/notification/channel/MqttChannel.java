package com.shelfj.notification.channel;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import jakarta.json.Json;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Device-facing push over MQTT: POS terminals, kiosk/back-store displays, and the platform console
 * subscribe to their own {@code shelfj/notifications/{tenantId}/{recipient}} topic and receive
 * alerts (e.g. StockBelowThreshold) in real time instead of polling {@code
 * /admin/notifications/shortage-alerts}. Selected via {@code shelfj.notification.channel=mqtt}.
 * Not for customer-facing push — browsers/phones don't speak MQTT natively; use SMTP/SMS for that.
 *
 * <p>The connection is established lazily on first send (not in the constructor) so a broker
 * that isn't up yet at boot does not fail service startup — see ARCHITECTURE §17 (services start
 * in any order). Once connected, the client reconnects automatically on drops; a publish attempted
 * while disconnected throws, so the caller (via {@link com.shelfj.notification.service.Notifier})
 * does not record the send and the Kafka consumer redelivers and retries.
 */
public final class MqttChannel implements NotificationChannel {

  private final Mqtt5BlockingClient client;
  private volatile boolean connected;

  public MqttChannel(
      String host, int port, String clientId, String username, String password, boolean tls) {
    var builder =
        Mqtt5Client.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig();
    if (tls) {
      builder = builder.sslWithDefaultConfig();
    }
    if (username != null && !username.isBlank()) {
      builder =
          builder
              .simpleAuth()
              .username(username)
              .password(
                  password == null
                      ? new byte[0]
                      : password.getBytes(StandardCharsets.UTF_8))
              .applySimpleAuth();
    }
    this.client = builder.buildBlocking();
  }

  @Override
  public String name() {
    return "MQTT";
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    ensureConnected();
    String topic = topic(tenantId, recipient);
    try {
      client
          .publishWith()
          .topic(topic)
          .qos(MqttQos.AT_LEAST_ONCE)
          .payload(payload(subject, body))
          .send();
    } catch (RuntimeException e) {
      throw new IllegalStateException("MQTT publish to " + topic + " failed: " + e.getMessage(), e);
    }
  }

  private synchronized void ensureConnected() {
    if (connected) {
      return;
    }
    client.connect();
    connected = true;
  }

  /**
   * Recipient is the topic key chosen by the caller (e.g. a store id) — devices subscribe to
   * exactly their own scope.
   */
  static String topic(UUID tenantId, String recipient) {
    return "shelfj/notifications/" + tenantId + "/" + recipient;
  }

  static byte[] payload(String subject, String body) {
    StringWriter out = new StringWriter();
    try (var writer = Json.createWriter(out)) {
      writer.writeObject(
          Json.createObjectBuilder()
              .add("subject", subject)
              .add("body", body)
              .add("sentAt", Instant.now().toString())
              .build());
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Called by {@link NotificationChannelProducer}'s disposer on application shutdown. */
  void close() {
    if (connected) {
      client.disconnect();
    }
  }
}
