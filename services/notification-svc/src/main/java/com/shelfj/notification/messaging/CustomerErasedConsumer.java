package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code shelfj.customer.customer-erased} and dispatches to {@link CustomerErasedHandler}.
 */
@ApplicationScoped
class CustomerErasedConsumer extends BaseKafkaConsumer {

  @Inject CustomerErasedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.customer-erased",
      defaultValue = "shelfj.customer.customer-erased")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-svc-customer-erased-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-customer-erased";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
