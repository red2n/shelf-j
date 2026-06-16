package com.shelfj.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request/response DTOs for customer-svc. tenant_id never appears in requests — it comes from the
 * JWT/TenantContext.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  public record RegisterCustomerRequest(
      @NotBlank @Email String email,
      String phone,
      @NotBlank String firstName,
      @NotBlank String lastName,
      String dob, // ISO-8601 date: yyyy-MM-dd
      String gender,
      Boolean gdprConsent) {}

  public record UpdateCustomerRequest(
      String phone,
      @NotBlank String firstName,
      @NotBlank String lastName,
      String dob,
      String gender,
      Boolean gdprConsent) {}

  public record AddAddressRequest(
      @NotBlank String type,
      @NotBlank String line1,
      String line2,
      String city,
      String state,
      @NotBlank String country,
      String pincode,
      Boolean isDefault) {}

  public record EarnPointsRequest(
      @NotNull @Positive BigDecimal points, String orderId, String reason) {}

  public record RedeemPointsRequest(
      @NotNull @Positive BigDecimal points, String orderId, String reason) {}

  public record AdjustPointsRequest(@NotNull BigDecimal points, String reason) {}

  public record IssueStoreCreditRequest(
      @NotNull @Positive BigDecimal amount, String currency, String orderId, String reason) {}

  public record RedeemStoreCreditRequest(
      @NotNull @Positive BigDecimal amount, String currency, String orderId, String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  public record CustomerResponse(
      String id,
      String email,
      String phone,
      String firstName,
      String lastName,
      String dob,
      String gender,
      String status,
      String gdprConsentAt,
      String createdAt,
      String updatedAt) {}

  public record AddressResponse(
      String id,
      String customerId,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      boolean isDefault,
      String createdAt) {}

  public record LoyaltyAccountResponse(
      String customerId, BigDecimal pointsBalance, BigDecimal lifetimePoints, String tier) {}

  public record LoyaltyLedgerEntryResponse(
      String id,
      String type,
      BigDecimal points,
      BigDecimal balanceAfter,
      String orderId,
      String reason,
      String createdAt) {}

  public record StoreCreditAccountResponse(
      String customerId, BigDecimal balance, String currency) {}

  public record CustomerListResponse(List<CustomerResponse> items, String nextCursor) {}
}
