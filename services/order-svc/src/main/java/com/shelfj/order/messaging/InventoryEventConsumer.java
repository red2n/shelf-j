package com.shelfj.order.messaging;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Gap #50 — SIM→POS direction. Polls StockReceived, StockDeducted, and StockAdjusted events from
 * inventory-svc and dispatches each to {@link InventoryEventHandler} to update the local
 * pos_stock_positions projection. Consumer lifecycle only; all logic is in the handler (SRP).
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

  private KafkaConsumer<String, String> consumer;
  private ScheduledExecutorService scheduler;
  private volatile boolean running;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager init */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "InventoryEvent consumer disabled");
      return;
    }
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-svc-inventory-sync");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    try {
      this.consumer = new KafkaConsumer<>(props);
      this.consumer.subscribe(List.of(stockReceivedTopic, stockDeductedTopic, stockAdjustedTopic));
      this.running = true;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "order-inventory-sync-consumer");
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
      LOG.log(
          Level.INFO,
          "InventoryEvent consumer started (topics={0},{1},{2})",
          stockReceivedTopic,
          stockDeductedTopic,
          stockAdjustedTopic);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "InventoryEvent consumer failed to start: " + e.getMessage());
    }
  }

  private void pollQuietly() {
    if (!running) return;
    try {
      var records = consumer.poll(Duration.ofMillis(500));
      records.forEach(rec -> handler.handle(rec.value()));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "InventoryEvent poll deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    running = false;
    if (scheduler != null) scheduler.shutdownNow();
    if (consumer != null) {
      try {
        consumer.close(Duration.ofSeconds(2));
      } catch (Exception ignored) {
      }
    }
  }
}
