package com.shelfj.inventory.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls product-svc's merchandising topics — {@code shelfj.catalog.shelf-capacity-published} and
 * {@code shelfj.catalog.fixture-retired} — and hands each record to {@link ShelfCapacityHandler}.
 */
@ApplicationScoped
class ShelfCapacityConsumer extends BaseKafkaConsumer {

  @Inject ShelfCapacityHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.shelf-capacity-published",
      defaultValue = "shelfj.catalog.shelf-capacity-published")
  String capacityTopic;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.fixture-retired",
      defaultValue = "shelfj.catalog.fixture-retired")
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
