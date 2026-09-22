package com.storeql.tenant.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Counts what businesses do (21.10) from the events of the services that do it: an order from
 * order-svc's {@code OrderPlaced}, a text from notification-svc's {@code SmsSent}. The count is
 * kept where the plan is, so a meter is read against its allowance without a call to anybody.
 */
@ApplicationScoped
class UsageConsumer extends BaseKafkaConsumer {

  @Inject UsageHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-placed",
      defaultValue = "storeql.order.order-placed")
  String orderTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.sms-sent",
      defaultValue = "storeql.notification.sms-sent")
  String smsTopic;

  @Override
  protected List<String> topics() {
    return List.of(orderTopic, smsTopic);
  }

  @Override
  protected String consumerName() {
    return "tenant-usage-consumer";
  }

  @Override
  protected String groupId() {
    return "tenant-svc-usage";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(smsTopic)) {
      handler.smsSent(value);
    } else {
      handler.orderPlaced(value);
    }
  }
}
