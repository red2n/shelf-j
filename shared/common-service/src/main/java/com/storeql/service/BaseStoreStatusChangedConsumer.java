package com.storeql.service;

import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Abstract Kafka consumer for {@code StoreStatusChanged} events. Subclasses supply only the
 * service-specific {@link #consumerName()} and {@link #groupId()}; all topic wiring and dispatch
 * logic is shared here.
 *
 * <p>CDI processes {@code @Inject} and {@code @ConfigProperty} on inherited fields, so concrete
 * subclasses get the handler and topic injected automatically.
 */
public abstract class BaseStoreStatusChangedConsumer extends BaseKafkaConsumer {

  @Inject StoreStatusChangedHandler handler;

  /** Topic to subscribe to. Property: {@code storeql.kafka.topics.store-status-changed}. */
  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.store-status-changed",
      defaultValue = "storeql.tenant.store-status-changed")
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
