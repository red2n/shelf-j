package com.shelfj.inventory.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.purchase.goods-received}. Polls the topic and dispatches
 * each record to {@link GoodsReceivedHandler}. Consumer lifecycle is inherited from {@link
 * BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class GoodsReceivedConsumer extends BaseKafkaConsumer {

  @Inject GoodsReceivedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.goods-received",
      defaultValue = "shelfj.purchase.goods-received")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "inventory-goods-received-consumer";
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
