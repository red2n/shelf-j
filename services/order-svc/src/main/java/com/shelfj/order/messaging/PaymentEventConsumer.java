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
 * Polls payment events and dispatches each record to {@link PaymentEventHandler}. Consumer
 * lifecycle only; all logic is in the handler (SRP). The shared {@link KafkaEventLoop} provides
 * manual offset commit with seek-back, so a failed record is redelivered instead of silently lost.
 */
@ApplicationScoped
class PaymentEventConsumer {

  private static final Logger LOG = System.getLogger(PaymentEventConsumer.class.getName());

  @Inject PaymentEventHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.payment",
      defaultValue = "shelfj.payment.payment-captured")
  String capturedTopic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager init — CDI beans are lazy */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "PaymentEvent consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "order-payment-consumer",
              bootstrap,
              "order-svc",
              List.of(capturedTopic),
              (topic, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "PaymentEvent consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
