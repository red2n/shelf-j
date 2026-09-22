package com.storeql.tenant.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response shapes of plans and packaging (21.8). */
public final class PlanDtos {

  private PlanDtos() {}

  /** A plan as the platform writes it. A new plan is always a draft; nothing here sells it. */
  @Schema(name = "PlanRequest")
  public record PlanRequest(
      @Schema(description = "What an API call and a person say: STARTER, GROWTH.")
          @NotBlank
          @Size(max = 40)
          String code,
      @NotBlank @Size(max = 120) String name,
      @Size(max = 2000) String description,
      @Schema(description = "MONTH or YEAR.") @NotBlank @Size(max = 10) String billingInterval,
      @Schema(description = "Days before the first bill; 0 for none.") @Min(0) @Max(365)
          Integer trialDays,
      @Schema(description = "Whether it appears on the price list a business may read.")
          Boolean isPublic,
      @Schema(description = "Where it sits in the list, low first.") @Min(0) @Max(9999)
          Integer sortOrder) {}

  /** A plan's price in one currency from a date. */
  @Schema(name = "PlanPriceRequest")
  public record PriceRequest(
      @Schema(description = "ISO 4217.") @NotBlank @Size(min = 3, max = 3) String currency,
      @Schema(description = "Per billing interval, before tax.")
          @NotNull
          @DecimalMin("0")
          @Digits(integer = 14, fraction = 4)
          BigDecimal amount,
      @Schema(description = "ISO date; today when absent. An earlier price is never edited.")
          @Size(max = 10)
          String effectiveFrom) {}

  /**
   * One thing a plan includes: a limit carries {@code limitValue} (absent means unlimited), a
   * feature carries {@code enabled}. A key the platform does not enforce is refused.
   */
  @Schema(name = "PlanGrantRequest")
  public record GrantRequest(
      @NotBlank @Size(max = 60) String key, @Min(0) Long limitValue, Boolean enabled) {}

  /** What a plan includes, replaced whole: a key left out is one the plan no longer names. */
  @Schema(name = "PlanGrantsRequest")
  public record GrantsRequest(@NotNull List<GrantRequest> grants) {
    public GrantsRequest {
      grants = grants == null ? List.of() : List.copyOf(grants);
    }
  }

  /** Which plan a business is put on, and why. */
  @Schema(name = "TenantPlanRequest")
  public record TenantPlanRequest(@NotNull java.util.UUID planId, @Size(max = 500) String reason) {}

  @Schema(name = "PlanPrice")
  public record PriceResponse(String currency, BigDecimal amount, String effectiveFrom) {}

  @Schema(name = "PlanGrant")
  public record GrantResponse(String key, String label, Long limitValue, Boolean enabled) {}

  @Schema(name = "Plan")
  public record PlanResponse(
      String id,
      String code,
      String name,
      String description,
      String status,
      String billingInterval,
      int trialDays,
      boolean isDefault,
      boolean isPublic,
      int sortOrder,
      List<PriceResponse> prices,
      List<GrantResponse> includes,
      @Schema(description = "What it includes of each meter each billing period (21.10)")
          List<PlanMeterResponse> meters,
      @Schema(description = "What each unit beyond the included costs, per currency, from a date")
          List<MeterPriceResponse> meterPrices) {
    public PlanResponse {
      prices = List.copyOf(prices);
      includes = List.copyOf(includes);
      meters = List.copyOf(meters);
      meterPrices = List.copyOf(meterPrices);
    }
  }

  /**
   * What a plan includes of one meter.
   *
   * @param included how many each billing period; null means unlimited
   * @param hard whether use beyond it is refused rather than charged
   */
  @Schema(name = "PlanMeter")
  public record PlanMeterResponse(
      String meter, String label, String unit, Long included, boolean hard) {}

  @Schema(name = "PlanMeterPrice")
  public record MeterPriceResponse(
      String meter, String currency, BigDecimal unitAmount, String effectiveFrom) {}

  /**
   * One limit against what the business is using.
   *
   * @param used null when the service that owns the count could not be reached — said as unknown
   *     rather than guessed at
   * @param limitValue null means unlimited
   */
  @Schema(name = "PlanUsage")
  public record UsageResponse(String key, String label, Long limitValue, Long used, boolean over) {}

  /**
   * The plan a business is on.
   *
   * @param note why there is no plan, when there is none
   */
  @Schema(name = "TenantPlan")
  public record TenantPlanResponse(PlanResponse plan, List<UsageResponse> usage, String note) {
    public TenantPlanResponse {
      usage = List.copyOf(usage);
    }
  }

  /** What a business is allowed, for the service that has to enforce it. */
  @Schema(name = "PlanGrants")
  public record GrantsResponse(List<GrantResponse> grants) {
    public GrantsResponse {
      grants = List.copyOf(grants);
    }
  }

  /** What the platform enforces, so a console can offer the keys rather than invent them. */
  @Schema(name = "PlanEntitlementCatalogue")
  public record CatalogueResponse(List<CatalogueEntry> entitlements) {
    public CatalogueResponse {
      entitlements = List.copyOf(entitlements);
    }
  }

  @Schema(name = "PlanEntitlementKey")
  public record CatalogueEntry(String key, String label, boolean limit, String enforcedBy) {}

  // ── metered usage (21.10) ───────────────────────────────────────────────────

  /**
   * What a plan includes of one meter.
   *
   * @param included how many each billing period; absent means unlimited
   * @param hard whether use beyond it is refused rather than charged; only a refusable meter may be
   */
  @Schema(name = "PlanMeterRequest")
  public record PlanMeterRequest(
      @NotBlank @Size(max = 20) String meter, @Min(0) Long included, Boolean hard) {}

  /**
   * What a plan includes of each meter, replaced whole: a meter left out is one it does not name.
   */
  @Schema(name = "PlanMetersRequest")
  public record PlanMetersRequest(@NotNull @Size(max = 20) List<PlanMeterRequest> meters) {
    public PlanMetersRequest {
      meters = meters == null ? List.of() : List.copyOf(meters);
    }
  }

  /** What one unit beyond the included costs, in a currency, from a date. */
  @Schema(name = "PlanMeterPriceRequest")
  public record MeterPriceRequest(
      @NotBlank @Size(max = 20) String meter,
      @Schema(description = "ISO 4217.") @NotBlank @Size(min = 3, max = 3) String currency,
      @Schema(description = "Per unit beyond the included, before tax; up to four places.")
          @NotNull
          @DecimalMin("0")
          @Digits(integer = 14, fraction = 4)
          BigDecimal unitAmount,
      @Schema(description = "ISO date; today when absent. An earlier price is never edited.")
          @Size(max = 10)
          String effectiveFrom) {}

  @Schema(name = "PlanMeterKeys")
  public record MeterCatalogueResponse(List<MeterCatalogueEntry> meters) {
    public MeterCatalogueResponse {
      meters = List.copyOf(meters);
    }
  }

  @Schema(name = "PlanMeterKey")
  public record MeterCatalogueEntry(
      String key, String label, String unit, boolean refusable, String countedBy) {}
}
