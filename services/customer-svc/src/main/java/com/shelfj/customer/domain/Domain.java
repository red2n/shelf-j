package com.shelfj.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for customer-svc (Customers, Addresses, Loyalty, StoreCredit). */
public final class Domain {

  private Domain() {}

  public record Customer(
      UUID id,
      UUID tenantId,
      String email,
      String phone,
      String firstName,
      String lastName,
      LocalDate dob,
      String gender,
      String status,
      Instant gdprConsentAt,
      Instant anonymizedAt,
      Instant createdAt,
      Instant updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_ANONYMIZED = "ANONYMIZED";
  }

  public record CustomerAddress(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      boolean isDefault,
      Instant createdAt) {

    public static final String TYPE_HOME = "HOME";
    public static final String TYPE_BILLING = "BILLING";
    public static final String TYPE_SHIPPING = "SHIPPING";
  }

  public record LoyaltyAccount(
      UUID id,
      UUID tenantId,
      UUID customerId,
      BigDecimal pointsBalance,
      BigDecimal lifetimePoints,
      String tier,
      Instant createdAt,
      Instant updatedAt) {

    public static final String TIER_BRONZE = "BRONZE";
    public static final String TIER_SILVER = "SILVER";
    public static final String TIER_GOLD = "GOLD";
    public static final String TIER_PLATINUM = "PLATINUM";

    /**
     * Recalculate tier from lifetime points. Thresholds: Bronze < 1000, Silver < 5000, Gold <
     * 20000.
     */
    public static String tierFor(BigDecimal lifetimePoints) {
      int lp = lifetimePoints.intValue();
      if (lp >= 20_000) return TIER_PLATINUM;
      if (lp >= 5_000) return TIER_GOLD;
      if (lp >= 1_000) return TIER_SILVER;
      return TIER_BRONZE;
    }
  }

  public record LoyaltyLedgerEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      BigDecimal points,
      BigDecimal balanceAfter,
      UUID orderId,
      String reason,
      Instant createdAt) {

    public static final String TYPE_EARN = "EARN";
    public static final String TYPE_REDEEM = "REDEEM";
    public static final String TYPE_EXPIRE = "EXPIRE";
    public static final String TYPE_ADJUST = "ADJUST";
  }

  public record StoreCreditAccount(
      UUID id,
      UUID tenantId,
      UUID customerId,
      BigDecimal balance,
      String currency,
      Instant createdAt,
      Instant updatedAt) {}

  public record StoreCreditLedgerEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String currency,
      UUID orderId,
      String reason,
      Instant createdAt) {

    public static final String TYPE_ISSUE = "ISSUE";
    public static final String TYPE_REDEEM = "REDEEM";
    public static final String TYPE_EXPIRE = "EXPIRE";
    public static final String TYPE_ADJUST = "ADJUST";
  }
}
