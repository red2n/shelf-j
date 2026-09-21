package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls published notices and dispatches each to {@link StoreBroadcastPublishedHandler}. */
@ApplicationScoped
class StoreBroadcastPublishedConsumer extends BaseKafkaConsumer {

  @Inject StoreBroadcastPublishedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.store-broadcast-published",
      defaultValue = "storeql.tenant.store-broadcast-published")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-store-broadcast-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
