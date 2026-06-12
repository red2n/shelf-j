package com.shelfj.notification.messaging;

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
 * Kafka infrastructure for {@code shelfj.inventory.stock-below-threshold}. Polls the topic and
 * dispatches each record to {@link ShortageAlertHandler}. Consumer lifecycle only; all logic is in
 * the handler (SRP). The shared {@link KafkaEventLoop} provides manual offset commit with
 * seek-back, so a failed record is redelivered instead of silently lost.
 */
@ApplicationScoped
class ShortageAlertConsumer {

  private static final Logger LOG = System.getLogger(ShortageAlertConsumer.class.getName());

  @Inject ShortageAlertHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.stock-below-threshold",
      defaultValue = "shelfj.inventory.stock-below-threshold")
  String topic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "ShortageAlert consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "notification-shortage-alert-consumer",
              bootstrap,
              "notification-svc",
              List.of(topic),
              (t, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "ShortageAlert consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
