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
 * Kafka infrastructure for {@code shelfj.tenant.tenant-created}. Polls the topic and dispatches
 * each record to {@link TenantCreatedHandler}. This class owns only the consumer lifecycle; all
 * business logic lives in the handler (SRP). The shared {@link KafkaEventLoop} provides manual
 * offset commit with seek-back, so a failed record is redelivered instead of silently lost.
 *
 * <p>Topic name is config-driven so it can be changed without recompiling (OCP). Disable entirely
 * with {@code shelfj.kafka.enabled=false}.
 */
@ApplicationScoped
class TenantCreatedConsumer {

  private static final Logger LOG = System.getLogger(TenantCreatedConsumer.class.getName());

  @Inject TenantCreatedHandler handler;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String bootstrap;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-created",
      defaultValue = "shelfj.tenant.tenant-created")
  String topic;

  private KafkaEventLoop loop;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!kafkaEnabled) {
      LOG.log(Level.INFO, "TenantCreated consumer disabled");
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              "iam-tenant-created-consumer",
              bootstrap,
              "iam-svc",
              List.of(topic),
              (t, value) -> handler.handle(value));
      loop.start();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "TenantCreated consumer failed to start: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (loop != null) {
      loop.close();
    }
  }
}
