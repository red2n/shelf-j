package com.shelfj.inventory.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls OrderFulfilled, OrderReturned and OrderCancelled events from order-svc and dispatches each
 * to {@link OrderEventHandler} to update inventory positions and checkout stock holds. Consumer
 * lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the handler
 * (SRP).
 */
@ApplicationScoped
class OrderEventConsumer extends BaseKafkaConsumer {

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-fulfilled",
      defaultValue = "shelfj.order.order-fulfilled")
  String fulfilledTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-returned",
      defaultValue = "shelfj.order.order-returned")
  String returnedTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-cancelled",
      defaultValue = "shelfj.order.order-cancelled")
  String cancelledTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-voided",
      defaultValue = "shelfj.order.order-voided")
  String voidedTopic;

  @Override
  protected List<String> topics() {
    return List.of(fulfilledTopic, returnedTopic, cancelledTopic, voidedTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-order-sync-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-order-sync";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
