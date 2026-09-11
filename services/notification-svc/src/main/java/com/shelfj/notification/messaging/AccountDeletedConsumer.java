package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls {@code shelfj.iam.account-deleted} and dispatches to {@link AccountDeletedHandler}. */
@ApplicationScoped
class AccountDeletedConsumer extends BaseKafkaConsumer {

  @Inject AccountDeletedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.account-deleted",
      defaultValue = "shelfj.iam.account-deleted")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-svc-account-deleted-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-account-deleted";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
