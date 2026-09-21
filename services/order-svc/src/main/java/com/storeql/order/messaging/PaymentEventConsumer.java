package com.storeql.order.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls payment-captured, payment-failed and payment-refunded events and dispatches each to {@link
 * PaymentEventHandler}. Consumer lifecycle is inherited from {@link BaseKafkaConsumer}; all
 * business logic lives in the handler (SRP).
 */
@ApplicationScoped
class PaymentEventConsumer extends BaseKafkaConsumer {

  @Inject PaymentEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment",
      defaultValue = "storeql.payment.payment-captured")
  String capturedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment-failed",
      defaultValue = "storeql.payment.payment-failed")
  String failedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment-refunded",
      defaultValue = "storeql.payment.payment-refunded")
  String refundedTopic;

  @Override
  protected List<String> topics() {
    return List.of(capturedTopic, failedTopic, refundedTopic);
  }

  @Override
  protected String consumerName() {
    return "order-payment-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
