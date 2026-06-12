package com.shelfj.order.messaging;

import com.shelfj.service.KafkaEventLoop;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Gap #50 — SIM→POS direction. Polls StockReceived, StockDeducted, and StockAdjusted events from
 * inventory-svc and dispatches each to {@link InventoryEventHandler} to update the local
 * pos_stock_positions projection. Consumer lifecycle only; all logic is in the handler (SRP). The
 * shared {@link KafkaEventLoop} provides manual offset commit with seek-back, so a failed record is
 * redelivered instead of silently lost.
 */
@ApplicationScoped
class InventoryEventConsumer {

  private static final Logger LOG = System.getLogger(InventoryEventConsumer.class.getName());

  @Inject InventoryEventHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.stock-received",
      defaultValue = "shelfj.inventory.stock-received")
  String stockReceivedTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.stock-deducted",
      defaultValue = "shelfj.inventory.stock-deducted")
  String stockDeductedTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.stock-adjusted",
      defaultValue = "shelfj.inventory.stock-adjusted")
  String stockAdjustedTopic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager init */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "InventoryEvent consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "order-inventory-sync-consumer",
              bootstrap,
              "order-svc-inventory-sync",
              List.of(stockReceivedTopic, stockDeductedTopic, stockAdjustedTopic),
              (topic, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "InventoryEvent consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
