package com.shelfj.cart.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
    return "cart-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-store-status";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
