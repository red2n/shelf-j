package com.shelfj.reporting.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Subscribes to all inventory events that feed reporting projections. Delegates dispatch to {@link
 * StockEventDispatcher} which routes by topic. Consumer lifecycle is inherited from {@link
 * BaseKafkaConsumer}; no business logic here (SRP).
 */
@ApplicationScoped
class StockEventConsumer extends BaseKafkaConsumer {

  private static final List<String> TOPICS =
      List.of(
          "shelfj.inventory.stock-received",
          "shelfj.inventory.stock-deducted",
          "shelfj.inventory.stock-adjusted",
          "shelfj.inventory.transfer-order-shipped",
          "shelfj.inventory.transfer-order-received");

  @Inject StockEventDispatcher dispatcher;

  @Override
  protected List<String> topics() {
    return TOPICS;
  }

  @Override
  protected String consumerName() {
    return "reporting-stock-event-consumer";
  }

  @Override
  protected String groupId() {
    return "reporting-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    dispatcher.dispatch(topic, value);
  }
}
