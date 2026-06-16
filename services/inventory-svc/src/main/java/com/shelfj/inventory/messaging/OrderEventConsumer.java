package com.shelfj.inventory.messaging;

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
 * Gap #50 — POS→SIM direction. Polls OrderFulfilled and OrderReturned events from order-svc and
 * dispatches each to {@link OrderEventHandler}. Consumer lifecycle only; all logic is in the
 * handler (SRP). The shared {@link KafkaEventLoop} provides manual offset commit with seek-back, so
 * a failed record is redelivered instead of silently lost.
 */
@ApplicationScoped
class OrderEventConsumer {

  private static final Logger LOG = System.getLogger(OrderEventConsumer.class.getName());

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

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
      LOG.log(Level.INFO, "OrderEvent consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "inventory-order-sync-consumer",
              bootstrap,
              "inventory-svc-order-sync",
              List.of(orderFulfilledTopic, orderReturnedTopic),
              (topic, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "OrderEvent consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
