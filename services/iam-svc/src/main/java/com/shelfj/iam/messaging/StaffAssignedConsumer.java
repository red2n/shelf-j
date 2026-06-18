package com.shelfj.iam.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code shelfj.tenant.staff-assigned}. Polls the topic and dispatches
 * each record to {@link StaffAssignedHandler}. Consumer lifecycle is inherited from {@link
 * BaseKafkaConsumer}; all business logic lives in the handler (SRP).
 */
@ApplicationScoped
class StaffAssignedConsumer extends BaseKafkaConsumer {

  @Inject StaffAssignedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.staff-assigned",
      defaultValue = "shelfj.tenant.staff-assigned")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "iam-staff-assigned-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-staff-assigned";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
