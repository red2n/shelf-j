package com.shelfj.cart.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
    return "cart-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-tenant-status";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
