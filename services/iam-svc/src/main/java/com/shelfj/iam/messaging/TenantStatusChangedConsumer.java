package com.shelfj.iam.messaging;

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
 * Kafka infrastructure for {@code shelfj.tenant.tenant-status-changed}. Polls the topic and
 * dispatches each record to {@link TenantStatusChangedHandler}. Owns only the consumer lifecycle;
 * business logic lives in the handler (SRP). Uses its own consumer group. Disable with {@code
 * shelfj.kafka.enabled=false}.
 */
@ApplicationScoped
class TenantStatusChangedConsumer {

  private static final Logger LOG = System.getLogger(TenantStatusChangedConsumer.class.getName());

  @Inject TenantStatusChangedHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-status-changed",
      defaultValue = "shelfj.tenant.tenant-status-changed")
  String topic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "TenantStatusChanged consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "iam-tenant-status-changed-consumer",
              bootstrap,
              "iam-svc-tenant-status",
              List.of(topic),
              (t, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "TenantStatusChanged consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
