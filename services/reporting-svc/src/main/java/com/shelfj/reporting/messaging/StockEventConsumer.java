package com.shelfj.reporting.messaging;

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
 * Subscribes to all inventory events that feed reporting projections. Delegates dispatch to {@link
 * StockEventDispatcher} which routes by topic. No business logic here (SRP). The shared {@link
 * KafkaEventLoop} provides manual offset commit with seek-back, so a failed record is redelivered
 * instead of silently lost.
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

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "StockEvent consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "reporting-stock-event-consumer",
              bootstrap,
              "reporting-svc",
              TOPICS,
              dispatcher::dispatch);
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "StockEvent consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
