package com.shelfj.notification.channel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import com.shelfj.test.EmqxSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the real EMQX broker + the actual infra/emqx.conf / infra/emqx-acl.conf (see {@link
 * EmqxSupport}) to prove three things end to end that unit tests can't: (1) a message published
 * through {@link MqttChannel} is actually received on the wire by a tenant's own subscriber; (2) a
 * different tenant cannot subscribe to that tenant's topic even with a validly-signed JWT of its
 * own; (3) a client cannot spoof a username that doesn't match its JWT's {@code tenant} claim.
 *
 * <p>This is the only place that can catch a broker misconfiguration — the EMQX config was written
 * without a broker available to test against, so this is the actual verification of it, not just of
 * the Java code.
 */
class MqttAclIT {

  private static final String JWT_SECRET = "test-secret-at-least-32-characters-long!";
  private static final String JWT_ISSUER = "shelfj";

  private static EmqxSupport broker;

  @BeforeAll
  static void startBroker() {
    broker = EmqxSupport.start(JWT_SECRET);
  }

  @AfterAll
  static void stopBroker() {
    broker.stop();
  }

  private static String signToken(String tenantClaim) {
    Instant now = Instant.now();
    return JWT.create()
        .withIssuer(JWT_ISSUER)
        .withSubject(tenantClaim)
        .withClaim("tenant", tenantClaim)
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(300))
        .sign(Algorithm.HMAC256(JWT_SECRET));
  }

  private static Mqtt5BlockingClient newClient() {
    return Mqtt5Client.builder()
        .serverHost(broker.host())
        .serverPort(broker.port())
        .buildBlocking();
  }

  private static void connect(Mqtt5BlockingClient client, String username, String password) {
    client
        .connectWith()
        .simpleAuth()
        .username(username)
        .password(password.getBytes(StandardCharsets.UTF_8))
        .applySimpleAuth()
        .send();
  }

  @Test
  void publishedAlertReachesItsOwnTenantsSubscriber() throws InterruptedException {
    UUID tenantA = UUID.randomUUID();
    MqttChannel publisher =
        new MqttChannel(
            broker.host(),
            broker.port(),
            "it-publisher",
            MqttPublisherToken.PUBLISHER_IDENTITY,
            MqttPublisherToken.mint(JWT_SECRET, JWT_ISSUER, 300),
            false);

    Mqtt5BlockingClient subscriber = newClient();
    connect(subscriber, tenantA.toString(), signToken(tenantA.toString()));
    try (Mqtt5BlockingClient.Mqtt5Publishes publishes =
        subscriber.publishes(MqttGlobalPublishFilter.ALL)) {
      Mqtt5SubAck subAck =
          subscriber
              .subscribeWith()
              .topicFilter(MqttChannel.topic(tenantA, "store-1"))
              .qos(MqttQos.AT_LEAST_ONCE)
              .send();
      assertTrue(
          subAck.getReasonCodes().stream().noneMatch(c -> c.isError()),
          "own-tenant subscribe must be granted");

      publisher.send(tenantA, "store-1", "Stock below threshold", "available 2 (threshold 5)");

      Optional<Mqtt5Publish> received = publishes.receive(10, TimeUnit.SECONDS);
      assertTrue(
          received.isPresent(), "subscriber must receive the alert published for its own tenant");
      String payload = new String(received.get().getPayloadAsBytes(), StandardCharsets.UTF_8);
      assertThat(payload, startsWith("{"));
    } finally {
      subscriber.disconnect();
      publisher.close();
    }
  }

  @Test
  void anotherTenantCannotSubscribeToATenantItDoesNotOwn() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    Mqtt5BlockingClient intruder = newClient();
    connect(intruder, tenantB.toString(), signToken(tenantB.toString()));
    try {
      Mqtt5SubAck subAck =
          intruder
              .subscribeWith()
              .topicFilter(MqttChannel.topic(tenantA, "store-1"))
              .qos(MqttQos.AT_LEAST_ONCE)
              .send();
      assertTrue(
          subAck.getReasonCodes().stream().anyMatch(c -> c.isError()),
          "a validly-authenticated tenant must still be denied another tenant's topic");
    } finally {
      intruder.disconnect();
    }
  }

  @Test
  void aClientCannotConnectWithAUsernameThatDoesNotMatchItsOwnJwtTenantClaim() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    Mqtt5BlockingClient spoofer = newClient();

    // Valid JWT for tenant A, but CONNECT claims to be tenant B — infra/emqx.conf's
    // verify_claims{tenant="${username}"} must reject this outright.
    assertThrows(
        RuntimeException.class,
        () -> connect(spoofer, tenantB.toString(), signToken(tenantA.toString())));
  }
}
