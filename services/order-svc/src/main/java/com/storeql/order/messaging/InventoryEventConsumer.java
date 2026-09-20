package com.storeql.order.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls StockReceived, StockDeducted, and StockAdjusted events from inventory-svc and dispatches
 * each to {@link InventoryEventHandler} to update the local pos_stock_positions projection.
 * Consumer lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the
 * handler (SRP).
 */
@ApplicationScoped
class InventoryEventConsumer extends BaseKafkaConsumer {

  @Inject InventoryEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.stock-received",
      defaultValue = "storeql.inventory.stock-received")
  String stockReceivedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.stock-deducted",
      defaultValue = "storeql.inventory.stock-deducted")
  String stockDeductedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.stock-adjusted",
      defaultValue = "storeql.inventory.stock-adjusted")
  String stockAdjustedTopic;

  @Override
  protected List<String> topics() {
    return List.of(stockReceivedTopic, stockDeductedTopic, stockAdjustedTopic);
  }

  @Override
  protected String consumerName() {
    return "order-inventory-sync-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-inventory-sync";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
