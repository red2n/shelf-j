package com.shelfj.cart.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.order.order-placed}. Dispatches each record to {@link
 * OrderPlacedHandler} to mark the customer's cart as CHECKED_OUT. Consumer lifecycle is inherited
 * from {@link BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class OrderPlacedConsumer extends BaseKafkaConsumer {

  @Inject OrderPlacedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-placed",
      defaultValue = "shelfj.order.order-placed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "cart-order-placed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-order-placed";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
