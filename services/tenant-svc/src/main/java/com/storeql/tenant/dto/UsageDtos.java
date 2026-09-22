package com.storeql.tenant.dto;

import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Usage metering and quotas (21.10): what a business used this period, and before. */
public final class UsageDtos {

  private UsageDtos() {}

  /**
   * One meter this period.
   *
   * @param included null when unlimited, or when the plan does not name the meter
   * @param unitAmount what each one beyond the included costs; null when nothing is charged for it
   * @param estimate what this period owes so far beyond the plan's own price
   */
  @Schema(name = "UsageMeterReading")
  public record MeterReading(
      String meter,
      String label,
      String unit,
      long used,
      Long included,
      @Schema(description = "Whether use beyond the included is refused rather than charged")
          boolean hard,
      long over,
      String currency,
      BigDecimal unitAmount,
      BigDecimal estimate) {}

  @Schema(name = "UsageAlert")
  public record AlertView(
      String meter,
      @Schema(description = "Percent of what is included: 80, then 100") int threshold,
      long used,
      long included,
      String raisedAt) {}

  /**
   * One closed period of one meter, as it was billed.
   *
   * @param notCharged why something over the allowance cost nothing: TRIAL or NOT_PRICED
   * @param invoiceId the invoice that carried it; null when none did
   */
  @Schema(name = "UsagePeriod")
  public record PeriodView(
      String meter,
      String periodStart,
      String periodEnd,
      long used,
      Long included,
      long overage,
      String currency,
      BigDecimal unitAmount,
      BigDecimal amount,
      String notCharged,
      String invoiceId) {}

  /**
   * What a business has used this billing period, and what it used before.
   *
   * @param periodEnd exclusive: the next period starts that day
   * @param trial whether this period is a trial, which is free whatever is used
   */
  @Schema(name = "Usage")
  public record UsageResponse(
      String periodStart,
      String periodEnd,
      boolean trial,
      String currency,
      boolean onAPlan,
      List<MeterReading> meters,
      List<AlertView> alerts,
      List<PeriodView> history) {
    public UsageResponse {
      meters = List.copyOf(meters);
      alerts = List.copyOf(alerts);
      history = List.copyOf(history);
    }
  }

  /**
   * Whether a business may do {@code quantity} more of a meter now.
   *
   * @param allowed false only when a hard ceiling would be passed
   */
  @Schema(name = "UsageAllowance")
  public record AllowanceResponse(
      String meter, long used, Long included, boolean hard, long quantity, boolean allowed) {}

  /** A threshold one business reached, as the platform reads it across businesses. */
  @Schema(name = "UsageAlertAcrossBusinesses")
  public record TenantAlertView(
      String tenantId,
      String meter,
      String periodStart,
      int threshold,
      long used,
      long included,
      String raisedAt) {}
}
