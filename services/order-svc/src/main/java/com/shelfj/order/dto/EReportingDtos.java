package com.shelfj.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** E-reporting: the transactions an invoice does not cover (18.9, second limb). */
public final class EReportingDtos {

  private EReportingDtos() {}

  @Schema(name = "EReportingRateLine")
  public record RateLineResponse(
      @Schema(description = "The rate's code in the business's own VAT table.") String vatCode,
      @Schema(description = "The fraction applied: 0.2000 for 20 per cent.") String vatRate,
      String net,
      String vat) {}

  /**
   * One day of trade.
   *
   * @param transactionCount operations in the day — one per sale, whatever rates its basket spanned
   */
  @Schema(name = "EReportingDay")
  public record DayResponse(
      String day, int transactionCount, String net, String vat, List<RateLineResponse> rates) {}

  @Schema(name = "EReportingCrossBorderLine")
  public record CrossBorderResponse(
      String invoiceNumber,
      String issueDate,
      @Schema(description = "From the buyer's VAT identifier, which is what is matched on.")
          String buyerCountry,
      String buyerVatId,
      String currency,
      String net,
      String vat) {}

  /**
   * What a period would report.
   *
   * @param currencies every currency the period took money in, so nothing is silently left out: a
   *     report is per currency, and a business that traded in two owes two
   * @param standing the submission that already stands for this period, when there is one
   */
  @Schema(name = "EReportingPreview")
  public record PreviewResponse(
      String returnCode,
      String periodStart,
      String periodEnd,
      String currency,
      List<String> currencies,
      int transactionCount,
      String netTotal,
      String vatTotal,
      @Schema(description = "True when the period has nothing in it — reported all the same.")
          boolean nothingToReport,
      List<DayResponse> days,
      List<CrossBorderResponse> crossBorder,
      SubmissionResponse standing) {}

  @Schema(name = "EReportingSubmission")
  public record SubmissionResponse(
      String id,
      @Schema(description = "EREPORTING_TX_FR or EREPORTING_PAY_FR.") String returnCode,
      String periodStart,
      String periodEnd,
      String currency,
      int transactionCount,
      String netTotal,
      String vatTotal,
      @Schema(description = "SHA-256 of what was sent, base64 — put this on the filing you record.")
          String payloadDigest,
      String network,
      String provider,
      @Schema(description = "PENDING, ACCEPTED or REJECTED.") String status,
      String detail,
      String providerRef,
      int attempts,
      String createdAt,
      String transmittedAt,
      @Schema(description = "The submission this corrects.") String supersedes,
      @Schema(description = "Set once a correction replaced this one.") String supersededBy) {}

  @Schema(name = "SubmitEReportingRequest")
  public record SubmitRequest(
      @Schema(description = "EREPORTING_TX_FR or EREPORTING_PAY_FR.") @NotBlank @Size(max = 40)
          String returnCode,
      @Schema(description = "The period's first day, as tenant-svc's calendar gives it.")
          @NotBlank
          @Size(max = 10)
          String periodStart,
      @Schema(description = "The day after the period's last — exclusive.")
          @NotBlank
          @Size(max = 10)
          String periodEnd,
      @Schema(description = "The currency to report; the business's own when left out.")
          @Size(max = 3)
          String currency,
      @Schema(
              description =
                  "The submission this corrects. A correction supersedes rather than replaces, and"
                      + " both stay on the record.")
          @Size(max = 36)
          String corrects) {}
}
