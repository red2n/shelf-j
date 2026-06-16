package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls stock-below-threshold events and dispatches each to {@link ShortageAlertHandler}. Consumer
 * lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the handler
 * (SRP).
 */
@ApplicationScoped
class ShortageAlertConsumer extends BaseKafkaConsumer {

  @Inject ShortageAlertHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.stock-below-threshold",
      defaultValue = "shelfj.inventory.stock-below-threshold")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-shortage-alert-consumer";
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
