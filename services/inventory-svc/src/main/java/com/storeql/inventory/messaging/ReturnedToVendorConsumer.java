package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code storeql.purchase.returned-to-vendor}. Polls the topic and
 * dispatches each record to {@link ReturnedToVendorHandler}; the lifecycle is {@link
 * BaseKafkaConsumer}'s and the business logic the handler's (SRP).
 */
@ApplicationScoped
class ReturnedToVendorConsumer extends BaseKafkaConsumer {

  @Inject ReturnedToVendorHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.returned-to-vendor",
      defaultValue = "storeql.purchase.returned-to-vendor")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "inventory-returned-to-vendor-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
