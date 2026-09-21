package com.storeql.reporting.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls tenant-svc's labour topic and hands each record to {@link LabourEventHandler}.
 *
 * <p>Its own consumer group, so it balances independently of the sales and stock consumers: a slow
 * labour projection must not hold up the sales one.
 */
@ApplicationScoped
class LabourEventConsumer extends BaseKafkaConsumer {

  @Inject LabourEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.labour-recorded",
      defaultValue = "storeql.tenant.labour-recorded")
  String labourTopic;

  @Override
  protected List<String> topics() {
    return List.of(labourTopic);
  }

  @Override
  protected String consumerName() {
    return "reporting-labour-event-consumer";
  }

  @Override
  protected String groupId() {
    return "reporting-svc-labour";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
