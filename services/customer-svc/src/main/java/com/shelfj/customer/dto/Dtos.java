package com.shelfj.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for customer-svc. tenant_id never appears in requests — it comes from the
 * JWT/TenantContext.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "RegisterCustomerRequest", description = "Customer registration.")
  public record RegisterCustomerRequest(
      @Schema(description = "Unique (per tenant) login/contact email.") @NotBlank @Email
          String email,
      String phone,
      @NotBlank String firstName,
      @NotBlank String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      String gender,
      @Schema(description = "True if the customer consented to GDPR data processing.")
          Boolean gdprConsent) {}

  @Schema(name = "UpdateCustomerRequest", description = "Update a customer's profile fields.")
  public record UpdateCustomerRequest(
      String phone,
      @NotBlank String firstName,
      @NotBlank String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      String gender,
      @Schema(description = "True if the customer consented to GDPR data processing.")
          Boolean gdprConsent) {}

  @Schema(name = "AddAddressRequest", description = "Add or replace a customer address.")
  public record AddAddressRequest(
      @Schema(description = "HOME, WORK, or other address type.") @NotBlank String type,
      @NotBlank String line1,
      String line2,
      String city,
      String state,
      @NotBlank String country,
      String pincode,
      @Schema(description = "True to mark this the customer's default address.")
          Boolean isDefault) {}

  @Schema(name = "EarnPointsRequest", description = "Manually award loyalty points.")
  public record EarnPointsRequest(
      @Schema(description = "Points to award; must be positive.") @NotNull @Positive
          BigDecimal points,
      @Schema(description = "UUID of the order this award relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "RedeemPointsRequest", description = "Redeem loyalty points.")
  public record RedeemPointsRequest(
      @Schema(description = "Points to redeem; must be positive and not exceed the balance.")
          @NotNull
          @Positive
          BigDecimal points,
      @Schema(description = "UUID of the order this redemption relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "AdjustPointsRequest", description = "Manual correction to a loyalty balance.")
  public record AdjustPointsRequest(
      @Schema(description = "Signed adjustment amount; may be negative.") @NotNull
          BigDecimal points,
      String reason) {}

  @Schema(name = "IssueStoreCreditRequest", description = "Issue store credit to a customer.")
  public record IssueStoreCreditRequest(
      @Schema(description = "Amount to issue; must be positive.") @NotNull @Positive
          BigDecimal amount,
      @Schema(description = "ISO currency code; defaults to GBP.") String currency,
      @Schema(description = "UUID of the order this issuance relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "RedeemStoreCreditRequest", description = "Redeem a customer's store credit.")
  public record RedeemStoreCreditRequest(
      @Schema(description = "Amount to redeem; must be positive and not exceed the balance.")
          @NotNull
          @Positive
          BigDecimal amount,
      @Schema(description = "ISO currency code; defaults to GBP.") String currency,
      @Schema(
              description =
                  "UUID of the order this redemption relates to; makes the redemption idempotent"
                      + " per order.")
          String orderId,
      String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "CustomerResponse", description = "A customer profile.")
  public record CustomerResponse(
      String id,
      String email,
      String phone,
      String firstName,
      String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      String gender,
      @Schema(description = "ACTIVE or ANONYMIZED.") String status,
      @Schema(description = "Timestamp GDPR consent was recorded; null if not given.")
          String gdprConsentAt,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "AddressResponse", description = "A customer address.")
  public record AddressResponse(
      String id,
      String customerId,
      @Schema(description = "HOME, WORK, or other address type.") String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "True if this is the customer's default address.") boolean isDefault,
      String createdAt) {}

  @Schema(name = "LoyaltyAccountResponse", description = "A customer's loyalty account.")
  public record LoyaltyAccountResponse(
      String customerId,
      @Schema(description = "Current redeemable points balance.") BigDecimal pointsBalance,
      @Schema(description = "Total points ever earned, never decremented.")
          BigDecimal lifetimePoints,
      @Schema(description = "BRONZE, SILVER, GOLD, etc.") String tier) {}

  @Schema(
      name = "LoyaltyLedgerEntryResponse",
      description = "One append-only entry in a customer's loyalty ledger.")
  public record LoyaltyLedgerEntryResponse(
      String id,
      @Schema(description = "EARN, REDEEM, or ADJUST.") String type,
      @Schema(description = "Signed points delta for this entry.") BigDecimal points,
      @Schema(description = "Points balance immediately after this entry.") BigDecimal balanceAfter,
      String orderId,
      String reason,
      String createdAt) {}

  @Schema(name = "StoreCreditAccountResponse", description = "A customer's store-credit balance.")
  public record StoreCreditAccountResponse(
      String customerId,
      @Schema(description = "Current redeemable store-credit balance.") BigDecimal balance,
      @Schema(description = "ISO currency code.") String currency) {}

  @Schema(name = "CustomerListResponse", description = "Cursor-paginated page of customers.")
  public record CustomerListResponse(
      List<CustomerResponse> items,
      @Schema(
              description =
                  "Cursor to pass as ?after= for the next page; null if this is the"
                      + " last page.")
          String nextCursor) {}
}
