package com.shelfj.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response shapes of settlement reconciliation (11.10). */
public final class SettlementDtos {

  private SettlementDtos() {}

  /**
   * One payout's settlement file. What the file says about itself — its payout number, its date,
   * its currency, the sum paid — wins over what is typed here; a layout that says none of it
   * (SHELFJ) needs them typed.
   */
  @Schema(name = "SettlementImportRequest")
  public record ImportRequest(
      @Schema(description = "Who paid: the acquirer or payment provider, as the business names it.")
          @NotBlank
          @Size(max = 30)
          String provider,
      @Schema(description = "The file's layout: one of GET /admin/settlements/formats.")
          @NotBlank
          @Size(max = 20)
          String format,
      @Schema(description = "The payout or batch number, when the file does not carry it.")
          @Size(max = 255)
          String reference,
      @Schema(
              description =
                  "When the money reached the bank (ISO date), when the file does not say.")
          @Size(max = 10)
          String payoutDate,
      @Schema(description = "ISO 4217; the file's, else the business's own currency.")
          @Size(min = 3, max = 3)
          String currency,
      @Schema(description = "What the bank statement shows was paid; the lines must add up to it.")
          @Digits(integer = 14, fraction = 4)
          BigDecimal declaredNet,
      @Schema(description = "The store a merchant account belongs to, when it is one store's.")
          java.util.UUID storeId,
      @Schema(description = "The file's text.") @NotBlank @Size(max = 5_000_000) String content) {}

  /** A manager's decision on a line that did not match. */
  @Schema(name = "SettlementResolveRequest")
  public record ResolveRequest(
      @Schema(description = "MATCHED_BY_HAND, DIFFERENCE_ACCEPTED or UNALLOCATED.")
          @NotBlank
          @Size(max = 24)
          String resolution,
      @Schema(
              description =
                  "The payment (for a sale), refund or dispute the line is about. Needed to match"
                      + " by hand; optional when accepting a difference on a line already linked.")
          java.util.UUID targetId,
      @Schema(description = "Why. Needed unless the line is simply matched by hand.")
          @Size(max = 1000)
          String note) {}

  @Schema(name = "SettlementBatch")
  public record BatchResponse(
      String id,
      String storeId,
      String provider,
      String reference,
      String format,
      String currency,
      String payoutDate,
      BigDecimal declaredNet,
      BigDecimal salesAmount,
      BigDecimal refundAmount,
      BigDecimal chargebackAmount,
      BigDecimal feeAmount,
      BigDecimal netAmount,
      int lineCount,
      int openExceptions,
      String status,
      String importedBy,
      String importedAt,
      String reconciledBy,
      String reconciledAt) {}

  @Schema(name = "SettlementLine")
  public record LineResponse(
      String id,
      int lineNo,
      String type,
      String reference,
      String originalReference,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      String occurredAt,
      String matchStatus,
      boolean open,
      String tenderId,
      String refundId,
      String disputeId,
      String storeId,
      @Schema(description = "What this service holds, when it differs from the line.")
          BigDecimal expectedAmount,
      String resolution,
      String resolvedBy,
      String resolvedAt,
      String note) {}

  /** A batch with a page of its lines. */
  @Schema(name = "SettlementBatchFile")
  public record BatchFileResponse(BatchResponse batch, List<LineResponse> lines) {
    public BatchFileResponse {
      lines = List.copyOf(lines);
    }
  }

  /** A card payment no settlement has covered yet. */
  @Schema(name = "UnsettledPayment")
  public record UnsettledResponse(
      String paymentId,
      String orderId,
      String storeId,
      String method,
      String reference,
      BigDecimal amount,
      String capturedAt,
      @Schema(description = "Whole days since it was taken.") long daysOutstanding) {}

  @Schema(name = "SettlementFormats")
  public record FormatsResponse(List<String> formats, int maxLines) {
    public FormatsResponse {
      formats = List.copyOf(formats);
    }
  }
}
