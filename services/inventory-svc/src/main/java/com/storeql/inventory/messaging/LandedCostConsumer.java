package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Kafka lifecycle for landed-cost events (07.x); the business logic is the handler's. */
@ApplicationScoped
class LandedCostConsumer extends BaseKafkaConsumer {

  @Inject LandedCostHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.landed-cost-applied",
      defaultValue = "storeql.purchase.landed-cost-applied")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "inventory-landed-cost-consumer";
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
