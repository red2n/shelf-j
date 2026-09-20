package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
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
      name = "storeql.kafka.topics.store-task-missed",
      defaultValue = "storeql.tenant.store-task-missed")
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
