package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls opened recalls and dispatches each to {@link RecallOpenedHandler}. */
@ApplicationScoped
class RecallOpenedConsumer extends BaseKafkaConsumer {

  @Inject RecallOpenedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.recall-opened",
      defaultValue = "shelfj.inventory.recall-opened")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-recall-opened-consumer";
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
