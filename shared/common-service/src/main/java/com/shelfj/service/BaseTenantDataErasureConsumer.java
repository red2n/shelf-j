package com.shelfj.service;

import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Abstract Kafka consumer for {@code TenantDataErasureDue} (21.14). A service subclasses it with
 * its {@link #consumerName()} and {@link #groupId()}; the erasure itself is {@link
 * TenantDataErasureHandler}'s.
 */
public abstract class BaseTenantDataErasureConsumer extends BaseKafkaConsumer {

  @Inject TenantDataErasureHandler handler;

  /** Property: {@code shelfj.kafka.topics.tenant-data-erasure-due}. */
  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-data-erasure-due",
      defaultValue = "shelfj.tenant.tenant-data-erasure-due")
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
