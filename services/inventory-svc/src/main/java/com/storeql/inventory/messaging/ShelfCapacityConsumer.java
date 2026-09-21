package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls product-svc's merchandising topics — {@code storeql.catalog.shelf-capacity-published} and
 * {@code storeql.catalog.fixture-retired} — and hands each record to {@link ShelfCapacityHandler}.
 */
@ApplicationScoped
class ShelfCapacityConsumer extends BaseKafkaConsumer {

  @Inject ShelfCapacityHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.shelf-capacity-published",
      defaultValue = "storeql.catalog.shelf-capacity-published")
  String capacityTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.fixture-retired",
      defaultValue = "storeql.catalog.fixture-retired")
  String fixtureRetiredTopic;

  @Override
  protected List<String> topics() {
    return List.of(capacityTopic, fixtureRetiredTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-shelf-capacity-consumer";
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
