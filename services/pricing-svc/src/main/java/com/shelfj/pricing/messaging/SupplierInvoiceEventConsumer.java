package com.shelfj.pricing.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** SJ-D39: input VAT arrives from purchase-svc as SupplierInvoiceCaptured. */
@ApplicationScoped
class SupplierInvoiceEventConsumer extends BaseKafkaConsumer {

  @Inject SupplierInvoiceEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.supplier-invoice-captured",
      defaultValue = "shelfj.purchase.supplier-invoice-captured")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "pricing-input-vat-consumer";
  }

  @Override
  protected String groupId() {
    return "pricing-svc-input-vat";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
