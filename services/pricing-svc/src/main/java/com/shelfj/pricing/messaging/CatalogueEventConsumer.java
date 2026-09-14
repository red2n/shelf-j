package com.shelfj.pricing.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The catalogue as product-svc announces it (03.8): products' categories and their variants. */
@ApplicationScoped
class CatalogueEventConsumer extends BaseKafkaConsumer {

  @Inject CatalogueEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.product-categorised",
      defaultValue = "shelfj.catalog.product-categorised")
  String categorised;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.variant-created",
      defaultValue = "shelfj.catalog.variant-created")
  String variantCreated;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.variant-measured",
      defaultValue = "shelfj.catalog.variant-measured")
  String variantMeasured;

  @Override
  protected List<String> topics() {
    return List.of(categorised, variantCreated, variantMeasured);
  }

  @Override
  protected String consumerName() {
    return "pricing-catalogue-consumer";
  }

  @Override
  protected String groupId() {
    return "pricing-svc-catalogue";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
