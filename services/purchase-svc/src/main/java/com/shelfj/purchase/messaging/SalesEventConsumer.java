package com.shelfj.purchase.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The sale as order-svc and payment-svc announce it, for the ledger (17.7). */
@ApplicationScoped
class SalesEventConsumer extends BaseKafkaConsumer {

  @Inject SalesEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.order-confirmed",
      defaultValue = "shelfj.order.order-confirmed")
  String orderConfirmed;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.payment-captured",
      defaultValue = "shelfj.payment.payment-captured")
  String paymentCaptured;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.payment-refunded",
      defaultValue = "shelfj.payment.payment-refunded")
  String paymentRefunded;

  // Chargebacks (11.9): the acquirer taking a card payment back, and how the dispute ended.
  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.dispute-opened",
      defaultValue = "shelfj.payment.dispute-opened")
  String disputeOpened;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.dispute-funds-withdrawn",
      defaultValue = "shelfj.payment.dispute-funds-withdrawn")
  String disputeFundsWithdrawn;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.dispute-closed",
      defaultValue = "shelfj.payment.dispute-closed")
  String disputeClosed;

  // A payout reconciled against the acquirer's settlement file (11.10).
  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.settlement-reconciled",
      defaultValue = "shelfj.payment.settlement-reconciled")
  String settlementReconciled;

  @Override
  protected List<String> topics() {
    return List.of(
        orderConfirmed,
        paymentCaptured,
        paymentRefunded,
        disputeOpened,
        disputeFundsWithdrawn,
        disputeClosed,
        settlementReconciled);
  }

  @Override
  protected String consumerName() {
    return "purchase-sales-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-sales";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(orderConfirmed)) {
      handler.orderConfirmed(value);
    } else if (topic.equals(paymentCaptured)) {
      handler.paymentCaptured(value);
    } else if (topic.equals(paymentRefunded)) {
      handler.paymentRefunded(value);
    } else if (topic.equals(disputeOpened) || topic.equals(disputeFundsWithdrawn)) {
      handler.disputeFundsTaken(value);
    } else if (topic.equals(disputeClosed)) {
      handler.disputeClosed(value);
    } else if (topic.equals(settlementReconciled)) {
      handler.settlementReconciled(value);
    }
  }
}
