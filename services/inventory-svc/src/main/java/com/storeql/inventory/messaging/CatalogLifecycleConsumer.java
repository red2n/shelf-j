package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls product-svc's lifecycle topics — {@code storeql.catalog.product-lifecycle} (discontinued,
 * launched, reinstated) and {@code storeql.catalog.product-delisted} — and hands each record to
 * {@link CatalogLifecycleHandler}.
 */
@ApplicationScoped
class CatalogLifecycleConsumer extends BaseKafkaConsumer {

  @Inject CatalogLifecycleHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.product-lifecycle",
      defaultValue = "storeql.catalog.product-lifecycle")
  String lifecycleTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.product-delisted",
      defaultValue = "storeql.catalog.product-delisted")
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
