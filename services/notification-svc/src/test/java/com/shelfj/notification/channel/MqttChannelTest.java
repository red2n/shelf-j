package com.shelfj.notification.channel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import jakarta.json.Json;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * No broker in these tests (that needs Testcontainers/a real MQTT connection — see NotificationIT
 * for the DB-backed integration test); this covers the two pure pieces of logic that decide tenant
 * isolation and wire format: topic scoping and payload shape.
 */
class MqttChannelTest {

  @Test
  void topicIsScopedByTenantThenRecipient() {
    UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");

    String topic = MqttChannel.topic(tenant, "store-42");

    assertThat(topic, is("shelfj/notifications/" + tenant + "/store-42"));
  }

  @Test
  void differentTenantsNeverShareATopic() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    String topicA = MqttChannel.topic(tenantA, "store-1");
    String topicB = MqttChannel.topic(tenantB, "store-1");

    assertThat(topicA.equals(topicB), is(false));
  }

  @Test
  void payloadIsJsonWithSubjectAndBody() {
    byte[] payload =
        MqttChannel.payload("Stock below threshold", "Variant X at store Y: 2 (threshold 5)");
    String json = new String(payload, StandardCharsets.UTF_8);

    var obj = Json.createReader(new StringReader(json)).readObject();

    assertThat(obj.getString("subject"), is("Stock below threshold"));
    assertThat(obj.getString("body"), containsString("threshold 5"));
    assertThat(obj.containsKey("sentAt"), is(true));
  }
}
