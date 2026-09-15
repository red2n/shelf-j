package com.shelfj.tenant.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Reads every service's {@code TenantDataErased} into the erasure evidence (21.14). */
@ApplicationScoped
class TenantDataErasedConsumer extends BaseKafkaConsumer {

  @Inject TenantDataErasedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.tenant-data-erased",
      defaultValue =
          "shelfj.iam.tenant-data-erased,shelfj.tenant.tenant-data-erased,"
              + "shelfj.product.tenant-data-erased,shelfj.inventory.tenant-data-erased,"
              + "shelfj.order.tenant-data-erased,shelfj.cart.tenant-data-erased,"
              + "shelfj.pricing.tenant-data-erased,shelfj.payment.tenant-data-erased,"
              + "shelfj.purchase.tenant-data-erased,shelfj.customer.tenant-data-erased,"
              + "shelfj.notification.tenant-data-erased,shelfj.reporting.tenant-data-erased")
  List<String> topicsCfg;

  @Override
  protected List<String> topics() {
    return topicsCfg;
  }

  @Override
  protected String consumerName() {
    return "tenant-data-erased-consumer";
  }

  @Override
  protected String groupId() {
    return "tenant-svc-tenant-data-erased";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
