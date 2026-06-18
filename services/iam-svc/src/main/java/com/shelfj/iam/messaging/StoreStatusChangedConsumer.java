package com.shelfj.iam.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.tenant.store-status-changed}. Polls the topic and
 * dispatches each record to {@link StoreStatusChangedHandler}. Consumer lifecycle is inherited from
 * {@link BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class StoreStatusChangedConsumer extends BaseKafkaConsumer {

  @Inject StoreStatusChangedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.store-status-changed",
      defaultValue = "shelfj.tenant.store-status-changed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "iam-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-store-status";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
