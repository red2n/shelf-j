package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code shelfj.iam.user-registered} and dispatches to {@link UserRegisteredHandler}, which
 * sends a welcome notification. Consumer lifecycle inherited from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class UserRegisteredConsumer extends BaseKafkaConsumer {

  @Inject UserRegisteredHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.user-registered",
      defaultValue = "shelfj.iam.user-registered")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-user-registered-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-user";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
