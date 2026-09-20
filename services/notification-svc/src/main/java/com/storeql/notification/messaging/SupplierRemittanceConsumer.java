package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code SupplierRemittanceIssued} from purchase-svc: the advice a paid supplier is emailed. */
@ApplicationScoped
class SupplierRemittanceConsumer extends BaseKafkaConsumer {

  @Inject SupplierRemittanceHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.supplier-remittance-issued",
      defaultValue = "storeql.purchase.supplier-remittance-issued")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-supplier-remittance-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-remittance";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
