package com.shelfj.inventory.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls product-svc's lifecycle topics — {@code shelfj.catalog.product-lifecycle} (discontinued,
 * launched, reinstated) and {@code shelfj.catalog.product-delisted} — and hands each record to
 * {@link CatalogLifecycleHandler}.
 */
@ApplicationScoped
class CatalogLifecycleConsumer extends BaseKafkaConsumer {

  @Inject CatalogLifecycleHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.product-lifecycle",
      defaultValue = "shelfj.catalog.product-lifecycle")
  String lifecycleTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.product-delisted",
      defaultValue = "shelfj.catalog.product-delisted")
  String delistedTopic;

  @Override
  protected List<String> topics() {
    return List.of(lifecycleTopic, delistedTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-catalog-lifecycle-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
