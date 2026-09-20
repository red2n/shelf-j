package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls missed store tasks and dispatches each to {@link StoreTaskMissedHandler}. */
@ApplicationScoped
class StoreTaskMissedConsumer extends BaseKafkaConsumer {

  @Inject StoreTaskMissedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.store-task-missed",
      defaultValue = "shelfj.tenant.store-task-missed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-store-task-missed-consumer";
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
