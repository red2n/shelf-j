package com.shelfj.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Drains a service's transactional outbox to Kafka on a timer (at-least-once delivery; consumers
 * must be idempotent). Shared across all services — each provides a {@link ServiceSettings} (for
 * Kafka config) and an {@link OutboxStore} (its repo). Resilient: if Kafka is down, rows stay
 * pending and retry on the next tick.
 *
 * <p>Eager startup ({@code @Observes @Initialized}) because CDI instantiates
 * {@code @ApplicationScoped} lazily — a {@code @PostConstruct}-only bean would never run.
 */
@ApplicationScoped
public class OutboxPublisher {

  private static final Logger LOG = System.getLogger(OutboxPublisher.class.getName());

  @Inject ServiceSettings settings;

  /** Optional: a service without an outbox (e.g. sample-svc) provides no OutboxStore bean. */
  @Inject Instance<OutboxStore> storeInstance;

  private OutboxStore store;
  private KafkaProducer<String, String> producer;
  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* makes the bean eager */
  }

  @PostConstruct
  void start() {
    if (!settings.kafkaEnabled()) {
      LOG.log(Level.INFO, "Outbox publisher disabled (kafka disabled)");
      return;
    }
    if (storeInstance.isUnsatisfied()) {
      LOG.log(Level.INFO, "Outbox publisher disabled (no OutboxStore — service has no outbox)");
      return;
    }
    this.store = storeInstance.get();
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrap());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
    this.producer = new KafkaProducer<>(props);

    long poll = settings.outboxPollSeconds();
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, settings.serviceName() + "-outbox-publisher");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(this::drainQuietly, poll, poll, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Outbox publisher started (bootstrap={0})", settings.kafkaBootstrap());
  }

  private void drainQuietly() {
    try {
      for (var row : store.pendingOutbox(100)) {
        try {
          producer
              .send(new ProducerRecord<>(row.topic(), row.id().toString(), row.payload()))
              .get();
          store.markPublished(row.id());
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Publish failed for outbox {0}: {1}", row.id(), e.getMessage());
          return; // broker likely down; retry next tick
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Outbox drain deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
    if (producer != null) producer.close(Duration.ofSeconds(2));
  }
}
