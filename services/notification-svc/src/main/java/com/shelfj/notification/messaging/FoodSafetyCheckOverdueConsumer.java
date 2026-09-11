package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls missed food-safety checks and dispatches each to {@link FoodSafetyCheckOverdueHandler}. */
@ApplicationScoped
class FoodSafetyCheckOverdueConsumer extends BaseKafkaConsumer {

  @Inject FoodSafetyCheckOverdueHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.food-safety-check-overdue",
      defaultValue = "shelfj.inventory.food-safety-check-overdue")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-food-safety-overdue-consumer";
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
