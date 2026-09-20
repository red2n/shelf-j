package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.order.order-confirmed} and dispatches to {@link OrderConfirmedHandler},
 * which emails the buyer an order confirmation. Its own consumer group, independent of the other
 * consumers. Lifecycle inherited from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class OrderConfirmedConsumer extends BaseKafkaConsumer {

  @Inject OrderConfirmedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-confirmed",
      defaultValue = "storeql.order.order-confirmed")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-order-confirmed-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-order";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
