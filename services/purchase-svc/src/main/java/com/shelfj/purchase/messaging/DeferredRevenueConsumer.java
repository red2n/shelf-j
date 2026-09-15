package com.shelfj.purchase.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Loyalty points as customer-svc announces them and gift cards as order-svc does (17.11). */
@ApplicationScoped
class DeferredRevenueConsumer extends BaseKafkaConsumer {

  @Inject DeferredRevenueHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.loyalty-earned",
      defaultValue = "shelfj.customer.loyalty-earned")
  String loyaltyEarned;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.loyalty-redeemed",
      defaultValue = "shelfj.customer.loyalty-redeemed")
  String loyaltyRedeemed;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.loyalty-adjusted",
      defaultValue = "shelfj.customer.loyalty-adjusted")
  String loyaltyAdjusted;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.gift-card-loaded",
      defaultValue = "shelfj.order.gift-card-loaded")
  String giftCardLoaded;

  @Override
  protected List<String> topics() {
    return List.of(loyaltyEarned, loyaltyRedeemed, loyaltyAdjusted, giftCardLoaded);
  }

  @Override
  protected String consumerName() {
    return "purchase-deferred-revenue-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-deferred-revenue";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(giftCardLoaded)) {
      handler.giftCardLoaded(value);
    } else {
      handler.loyalty(value);
    }
  }
}
