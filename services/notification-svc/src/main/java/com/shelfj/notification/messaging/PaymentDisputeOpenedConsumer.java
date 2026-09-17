package com.shelfj.notification.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls chargebacks as payment-svc opens them and dispatches each to the handler (11.9). */
@ApplicationScoped
class PaymentDisputeOpenedConsumer extends BaseKafkaConsumer {

  @Inject PaymentDisputeOpenedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.dispute-opened",
      defaultValue = "shelfj.payment.dispute-opened")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-dispute-opened-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
