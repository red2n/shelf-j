package com.storeql.order.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls the orders a recall reached and dispatches each to {@link RecallSaleAffectedHandler}. */
@ApplicationScoped
class RecallSaleAffectedConsumer extends BaseKafkaConsumer {

  @Inject RecallSaleAffectedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.recall-sale-affected",
      defaultValue = "storeql.inventory.recall-sale-affected")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "order-recall-sale-affected-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-recall-notices";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
