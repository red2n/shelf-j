package com.shelfj.purchase.messaging;

import com.shelfj.purchase.domain.Domain.GiftCardLoad;
import com.shelfj.purchase.domain.Domain.LoyaltyEvent;
import com.shelfj.purchase.service.DeferredRevenueService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Reads what customer-svc announces about loyalty points and order-svc about gift cards, and hands
 * it to {@link DeferredRevenueService} (17.11). A payload that is not what its producer sends is
 * logged and skipped, since redelivering it would never make it parse; a failure to post
 * propagates, so the record comes again and the event, recorded once, is posted once. A loyalty
 * event published before 17.11 carries no id or type and is not posted.
 */
@ApplicationScoped
public class DeferredRevenueHandler {

  private static final Logger LOG = System.getLogger(DeferredRevenueHandler.class.getName());

  @Inject DeferredRevenueService deferred;

  /** {@code LoyaltyEarned}, {@code LoyaltyRedeemed} or {@code LoyaltyAdjusted}. */
  public void loyalty(String json) {
    LoyaltyEvent event;
    try {
      JsonObject o = EventJson.parse(json);
      String kind =
          switch (o.getString("eventType", "")) {
            case "LoyaltyEarned" -> LoyaltyEvent.EARNED;
            case "LoyaltyRedeemed" -> LoyaltyEvent.REDEEMED;
            case "LoyaltyAdjusted" -> LoyaltyEvent.ADJUSTED;
            default -> null;
          };
      if (kind == null) return;
      event =
          new LoyaltyEvent(
              UUID.fromString(o.getString("tenantId")),
              UUID.fromString(o.getString("eventId")),
              kind,
              EventJson.optUuid(o, "customerId"),
              EventJson.optUuid(o, "orderId"),
              o.getJsonNumber("points").bigDecimalValue(),
              EventJson.optNumber(o, "orderTotal"),
              EventJson.optNumber(o, "orderTaxAmount"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Loyalty event not posted, malformed: " + e.getMessage());
      return;
    }
    deferred.loyaltyEvent(event);
  }

  /** {@code GiftCardLoaded}: a card issued or reloaded, and how it was paid for. */
  public void giftCardLoaded(String json) {
    GiftCardLoad load;
    try {
      JsonObject o = EventJson.parse(json);
      if (!"GiftCardLoaded".equals(o.getString("eventType", ""))) return;
      load =
          new GiftCardLoad(
              UUID.fromString(o.getString("tenantId")),
              UUID.fromString(o.getString("transactionId")),
              UUID.fromString(o.getString("giftCardId")),
              EventJson.optUuid(o, "storeId"),
              o.getString("kind"),
              o.getString("paidBy"),
              o.getJsonNumber("amount").bigDecimalValue(),
              o.getString("currency"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "GiftCardLoaded not posted, malformed: " + e.getMessage());
      return;
    }
    deferred.giftCardLoaded(load);
  }
}
