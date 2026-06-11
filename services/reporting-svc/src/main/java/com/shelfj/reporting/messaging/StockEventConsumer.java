package com.shelfj.reporting.messaging;

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
 * Subscribes to all inventory events that feed reporting projections. Delegates dispatch to {@link
 * StockEventDispatcher} which routes by topic. No business logic here (SRP).
 */
@ApplicationScoped
class StockEventConsumer {

  private static final Logger LOG = System.getLogger(StockEventConsumer.class.getName());

  private static final List<String> TOPICS =
      List.of(
          "shelfj.inventory.stock-received",
          "shelfj.inventory.stock-deducted",
          "shelfj.inventory.stock-adjusted",
          "shelfj.inventory.transfer-order-shipped",
          "shelfj.inventory.transfer-order-received");

  @Inject StockEventDispatcher dispatcher;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  private KafkaConsumer<String, String> consumer;
  private ScheduledExecutorService scheduler;
  private volatile boolean running;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "StockEvent consumer disabled");
      return;
    }
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "reporting-svc");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    try {
      this.consumer = new KafkaConsumer<>(props);
      this.consumer.subscribe(TOPICS);
      this.running = true;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "reporting-stock-consumer");
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
      LOG.log(Level.INFO, "StockEvent consumer started (bootstrap={0})", bootstrap);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "StockEvent consumer failed to start: " + e.getMessage());
    }
  }

  private void pollQuietly() {
    if (!running) return;
    try {
      var records = consumer.poll(Duration.ofMillis(500));
      records.forEach(rec -> dispatcher.dispatch(rec.topic(), rec.value()));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "StockEvent poll deferred: " + e.getMessage());
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
