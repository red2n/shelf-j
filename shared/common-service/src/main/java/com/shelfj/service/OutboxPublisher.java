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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
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

  /** Optional: a service without an outbox provides no OutboxStore bean; publisher no-ops. */
  @Inject Instance<OutboxStore> storeInstance;

  private OutboxStore store;
  private KafkaProducer<String, String> producer;
  private ScheduledExecutorService scheduler;

  /**
   * CDI observer — makes this {@code @ApplicationScoped} bean eager so {@link #start()} runs at
   * application startup instead of never (lazy beans are only instantiated on first injection, and
   * nothing injects {@code OutboxPublisher} directly).
   *
   * @param event the CDI initialization event payload; unused, only its firing matters
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* makes the bean eager */
  }

  /**
   * Builds the Kafka producer and starts the drain-timer, or no-ops if Kafka is disabled or this
   * service has no {@link OutboxStore} bean (no outbox table).
   */
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
    // A service may have several repositories extending BaseOutboxRepository (= several
    // OutboxStore beans), but they all drain the same schema-level outbox table — any one
    // suffices. Instance.get() would throw AmbiguousResolutionException here.
    this.store = storeInstance.iterator().next();
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

  /**
   * One drain tick: claims up to 100 pending rows via {@link #store} and publishes them. Never
   * throws — any failure (claim query error, Kafka unreachable) is logged and deferred to the next
   * tick, since rows that aren't confirmed published simply stay pending.
   */
  private void drainQuietly() {
    try {
      // The claim (FOR UPDATE SKIP LOCKED) and the published-mark below run in the repo's single
      // transaction, so two replicas draining at the same instant never claim the same row.
      store.drainAndPublish(100, this::publishBatch);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Outbox drain deferred: " + e.getMessage());
    }
  }

  /**
   * Pipelines the whole batch (one flush) instead of awaiting each send, returning exactly the ids
   * that were confirmed delivered — N Kafka roundtrips become ~1. A row that fails to send isn't
   * returned, so it stays unpublished and retries next tick (at-least-once).
   *
   * @param rows the pending rows claimed by {@link #drainQuietly()}
   * @return the ids of {@code rows} whose send was confirmed by the broker; a subset when some
   *     sends failed or timed out
   */
  private List<UUID> publishBatch(List<OutboxStore.PendingOutbox> rows) {
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>(rows.size());
    for (var row : rows) {
      futures.add(
          producer.send(new ProducerRecord<>(row.topic(), row.id().toString(), row.payload())));
    }
    producer.flush();
    var published = new java.util.ArrayList<UUID>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      try {
        futures.get(i).get();
        published.add(rows.get(i).id());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        LOG.log(
            Level.WARNING, "Publish failed for outbox {0}: {1}", rows.get(i).id(), e.getMessage());
      }
    }
    return published;
  }

  /** Stops the drain timer and closes the producer, if either was started. */
  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
    if (producer != null) producer.close(Duration.ofSeconds(2));
  }
}
