package com.shelfj.customer.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.order.order-confirmed}. Polls the topic and dispatches
 * each record to {@link OrderConfirmedHandler}, which accrues loyalty for the buyer. Consumer
 * lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the handler
 * (SRP).
 */
@ApplicationScoped
class OrderConfirmedConsumer extends BaseKafkaConsumer {

  @Inject OrderConfirmedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-confirmed",
      defaultValue = "shelfj.order.order-confirmed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "customer-order-confirmed-consumer";
  }

  @Override
  protected String groupId() {
    return "customer-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
