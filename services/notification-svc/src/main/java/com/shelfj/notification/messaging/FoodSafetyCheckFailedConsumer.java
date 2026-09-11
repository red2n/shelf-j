package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls food-safety check failures and dispatches each to {@link FoodSafetyCheckFailedHandler}. */
@ApplicationScoped
class FoodSafetyCheckFailedConsumer extends BaseKafkaConsumer {

  @Inject FoodSafetyCheckFailedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.food-safety-check-failed",
      defaultValue = "shelfj.inventory.food-safety-check-failed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-food-safety-failed-consumer";
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
