package com.shelfj.payment.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for the automatic-refund path. Polls {@code shelfj.order.order-returned},
 * {@code shelfj.order.order-cancelled} and {@code shelfj.order.container-deposit-refunded} (09.16:
 * the deposit paid back at the till leaves the drawer) and dispatches each record to {@link
 * OrderEventHandler}. Consumer lifecycle is inherited from {@link BaseKafkaConsumer}; all business
 * logic lives in the handler (SRP).
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

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.container-deposit-refunded",
      defaultValue = "shelfj.order.container-deposit-refunded")
  String containerRefundTopic;

  @Override
  protected List<String> topics() {
    return List.of(returnedTopic, cancelledTopic, containerRefundTopic);
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
