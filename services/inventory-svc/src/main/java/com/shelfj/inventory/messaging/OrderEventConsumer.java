package com.shelfj.inventory.messaging;

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
 * Gap #50 — POS→SIM direction. Polls OrderFulfilled and OrderReturned events from order-svc and
 * dispatches each to {@link OrderEventHandler} to apply the stock movement. Consumer lifecycle
 * only; all logic is in the handler (SRP).
 */
@ApplicationScoped
class OrderEventConsumer {

  private static final Logger LOG = System.getLogger(OrderEventConsumer.class.getName());

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-fulfilled",
      defaultValue = "shelfj.order.order-fulfilled")
  String orderFulfilledTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-returned",
      defaultValue = "shelfj.order.order-returned")
  String orderReturnedTopic;

  private KafkaConsumer<String, String> consumer;
  private ScheduledExecutorService scheduler;
  private volatile boolean running;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager init */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "OrderEvent consumer disabled");
      return;
    }
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-svc-order-sync");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    try {
      this.consumer = new KafkaConsumer<>(props);
      this.consumer.subscribe(List.of(orderFulfilledTopic, orderReturnedTopic));
      this.running = true;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "inventory-order-sync-consumer");
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
      LOG.log(
          Level.INFO,
          "OrderEvent consumer started (topics={0},{1})",
          orderFulfilledTopic,
          orderReturnedTopic);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "OrderEvent consumer failed to start: " + e.getMessage());
    }
  }

  private void pollQuietly() {
    if (!running) return;
    try {
      var records = consumer.poll(Duration.ofMillis(500));
      records.forEach(rec -> handler.handle(rec.value()));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "OrderEvent poll deferred: " + e.getMessage());
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
