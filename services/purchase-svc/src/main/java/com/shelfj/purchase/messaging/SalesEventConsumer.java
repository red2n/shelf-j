package com.shelfj.purchase.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The sale as order-svc and payment-svc announce it, for the ledger (17.7). */
@ApplicationScoped
class SalesEventConsumer extends BaseKafkaConsumer {

  @Inject SalesEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-confirmed",
      defaultValue = "shelfj.order.order-confirmed")
  String orderConfirmed;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.payment-captured",
      defaultValue = "shelfj.payment.payment-captured")
  String paymentCaptured;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.payment-refunded",
      defaultValue = "shelfj.payment.payment-refunded")
  String paymentRefunded;

  @Override
  protected List<String> topics() {
    return List.of(orderConfirmed, paymentCaptured, paymentRefunded);
  }

  @Override
  protected String consumerName() {
    return "purchase-sales-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-sales";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(orderConfirmed)) {
      handler.orderConfirmed(value);
    } else if (topic.equals(paymentCaptured)) {
      handler.paymentCaptured(value);
    } else if (topic.equals(paymentRefunded)) {
      handler.paymentRefunded(value);
    }
  }
}
