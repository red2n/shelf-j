package com.storeql.tenant.messaging;

import com.storeql.service.BaseKafkaConsumer;
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
      name = "storeql.kafka.topics.tenant-data-erased",
      defaultValue =
          "storeql.iam.tenant-data-erased,storeql.tenant.tenant-data-erased,"
              + "storeql.product.tenant-data-erased,storeql.inventory.tenant-data-erased,"
              + "storeql.order.tenant-data-erased,storeql.cart.tenant-data-erased,"
              + "storeql.pricing.tenant-data-erased,storeql.payment.tenant-data-erased,"
              + "storeql.purchase.tenant-data-erased,storeql.customer.tenant-data-erased,"
              + "storeql.notification.tenant-data-erased,storeql.reporting.tenant-data-erased")
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
