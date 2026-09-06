package com.shelfj.order.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.tenant.tenant-created}. Polls the topic and dispatches
 * each record to {@link TenantCreatedHandler}. Consumer lifecycle is inherited from {@link
 * BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class TenantCreatedConsumer extends BaseKafkaConsumer {

  @Inject TenantCreatedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-created",
      defaultValue = "shelfj.tenant.tenant-created")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "order-tenant-created-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
