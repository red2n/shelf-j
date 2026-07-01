package com.shelfj.service;

import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Abstract Kafka consumer for {@code TenantStatusChanged} events. Subclasses supply only the
 * service-specific {@link #consumerName()} and {@link #groupId()}; all topic wiring and dispatch
 * logic is shared here.
 *
 * <p>CDI processes {@code @Inject} and {@code @ConfigProperty} on inherited fields, so concrete
 * subclasses get the handler and topic injected automatically.
 */
public abstract class BaseTenantStatusChangedConsumer extends BaseKafkaConsumer {

  @Inject TenantStatusChangedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-status-changed",
      defaultValue = "shelfj.tenant.tenant-status-changed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
