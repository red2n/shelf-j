package com.storeql.tenant.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Keeps the register of purges (21.16): every service that purges announces each run as {@code
 * RetentionRunCompleted} on its own topic, and this service records them all in one place, once
 * each. The first consumer tenant-svc has needed: its reference data is read by every service, and
 * this is the one fact the others hold that it has to keep.
 */
@ApplicationScoped
class RetentionRunConsumer extends BaseKafkaConsumer {

  @Inject RetentionRunHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-retention-run",
      defaultValue = "storeql.order.retention-run-completed")
  String orderTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.customer-retention-run",
      defaultValue = "storeql.customer.retention-run-completed")
  String customerTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.notification-retention-run",
      defaultValue = "storeql.notification.retention-run-completed")
  String notificationTopic;

  @Override
  protected List<String> topics() {
    return List.of(orderTopic, customerTopic, notificationTopic);
  }

  @Override
  protected String consumerName() {
    return "tenant-retention-run-consumer";
  }

  @Override
  protected String groupId() {
    return "tenant-svc-retention-runs";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
