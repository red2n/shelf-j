package com.shelfj.iam.messaging;

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
 * Kafka infrastructure for {@code shelfj.tenant.staff-assigned}. Polls the topic and dispatches
 * each record to {@link StaffAssignedHandler}. This class owns only the consumer lifecycle; all
 * business logic lives in the handler (SRP).
 *
 * <p>Uses its own consumer group so it never competes with {@link TenantCreatedConsumer} during
 * rebalances. Topic name is config-driven (OCP). Disable entirely with {@code
 * shelfj.kafka.enabled=false}.
 */
@ApplicationScoped
class StaffAssignedConsumer {

  private static final Logger LOG = System.getLogger(StaffAssignedConsumer.class.getName());

  @Inject StaffAssignedHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.staff-assigned",
      defaultValue = "shelfj.tenant.staff-assigned")
  String topic;

  private KafkaConsumer<String, String> consumer;
  private ScheduledExecutorService scheduler;
  private volatile boolean running;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "StaffAssigned consumer disabled");
      return;
    }
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "iam-svc-staff-assigned");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    try {
      this.consumer = new KafkaConsumer<>(props);
      this.consumer.subscribe(List.of(topic));
      this.running = true;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "iam-staff-assigned-consumer");
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
      LOG.log(
          Level.INFO,
          "StaffAssigned consumer started (bootstrap={0}, topic={1})",
          bootstrap,
          topic);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "StaffAssigned consumer failed to start: " + e.getMessage());
    }
  }

  private void pollQuietly() {
    if (!running) return;
    try {
      var records = consumer.poll(Duration.ofMillis(500));
      records.forEach(rec -> handler.handle(rec.value()));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "StaffAssigned poll deferred: " + e.getMessage());
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
