package com.shelfj.order.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.customer.customer-erased}. Dispatches each record to
 * {@link CustomerErasedHandler}; lifecycle comes from {@link BaseKafkaConsumer}.
 */
@ApplicationScoped
class CustomerErasedConsumer extends BaseKafkaConsumer {

  @Inject CustomerErasedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.customer-erased",
      defaultValue = "shelfj.customer.customer-erased")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "order-customer-erased-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-customer-erased";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
