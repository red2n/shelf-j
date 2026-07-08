package com.shelfj.payment.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for the automatic-refund path. Polls {@code shelfj.order.order-returned} and
 * {@code shelfj.order.order-cancelled} and dispatches each record to {@link OrderEventHandler}.
 * Consumer lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the
 * handler (SRP).
 */
@ApplicationScoped
class OrderEventConsumer extends BaseKafkaConsumer {

  @Inject OrderEventHandler handler;

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

  @Override
  protected List<String> topics() {
    return List.of(returnedTopic, cancelledTopic);
  }

  @Override
  protected String consumerName() {
    return "payment-order-refund-consumer";
  }

  @Override
  protected String groupId() {
    return "payment-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
