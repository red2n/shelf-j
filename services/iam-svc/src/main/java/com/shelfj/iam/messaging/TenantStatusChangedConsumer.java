package com.shelfj.iam.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.tenant.tenant-status-changed}. Polls the topic and
 * dispatches each record to {@link TenantStatusChangedHandler}. Consumer lifecycle is inherited
 * from {@link BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class TenantStatusChangedConsumer extends BaseKafkaConsumer {

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
  protected String consumerName() {
    return "iam-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-tenant-status";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
