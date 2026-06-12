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
 * Kafka infrastructure for {@code shelfj.purchase.goods-received}. Polls the topic and dispatches
 * each record to {@link GoodsReceivedHandler}. Consumer lifecycle only; all logic is in the handler
 * (SRP). The shared {@link KafkaEventLoop} provides manual offset commit with seek-back, so a
 * failed record is redelivered instead of silently lost.
 */
@ApplicationScoped
class GoodsReceivedConsumer {

  private static final Logger LOG = System.getLogger(GoodsReceivedConsumer.class.getName());

  @Inject GoodsReceivedHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.goods-received",
      defaultValue = "shelfj.purchase.goods-received")
  String topic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "GoodsReceived consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "inventory-goods-received-consumer",
              bootstrap,
              "inventory-svc",
              List.of(topic),
              (t, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "GoodsReceived consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
