package com.storeql.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for a recall's notices to buyers. No tenant_id in requests. */
public final class RecallNoticeDtos {

  private RecallNoticeDtos() {}

  @Schema(name = "RecallRemedyRequest", description = "The remedy the buyer chooses.")
  public record RemedyRequest(
      @Schema(enumeration = {"REPAIR", "REPLACEMENT", "REFUND"})
          @NotBlank
          @Pattern(regexp = "REPAIR|REPLACEMENT|REFUND", message = "remedy is not recognised")
          String remedy) {}

  @Schema(name = "RecallResolveRequest", description = "How the notice was settled.")
  public record ResolveRequest(
      @Schema(
              description = "A refund is recorded as a return naming the notice, not here.",
              enumeration = {"REPLACED", "REPAIRED", "DECLINED"})
          @NotBlank
          @Pattern(
              regexp = "REFUNDED|REPLACED|REPAIRED|DECLINED",
              message = "resolution is not recognised")
          String resolution,
      @Size(max = 2000) String notes) {}

  @Schema(name = "RecallNoticeLine")
  public record LineResponse(
      String variantId,
      @Schema(description = "As product-svc named it when the notice was issued; may be absent.")
          String productName,
      String sku,
      String batchNo,
      String expiryDate,
      BigDecimal qty,
      @Schema(enumeration = {"IN_SCOPE", "LOT_UNKNOWN", "DATE_UNKNOWN"}) String match) {}

  @Schema(name = "RecallNotice", description = "A recall's notice to the buyer of one order.")
  public record NoticeResponse(
      String id,
      String recallId,
      String reference,
      String hazard,
      String reason,
      String customerNotice,
      List<String> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      String orderId,
      String storeId,
      String channel,
      String customerId,
      String loginId,
      @Schema(description = "Whether the order names anyone the notice can reach.")
          boolean buyerIdentified,
      String soldAt,
      @Schema(enumeration = {"ISSUED", "UNIDENTIFIED", "REMEDY_CHOSEN", "RESOLVED"}) String status,
      String issuedAt,
      String remedy,
      String remedyChosenAt,
      @Schema(enumeration = {"SHOPPER", "STAFF"}) String remedyChosenVia,
      @Schema(enumeration = {"REFUNDED", "REPLACED", "REPAIRED", "DECLINED"}) String resolution,
      String resolvedAt,
      String returnId,
      String resolutionNotes,
      List<LineResponse> lines) {}

  @Schema(name = "RecallNoticeProgress", description = "How a recall's buyers stand.")
  public record ProgressResponse(
      String recallId,
      int notices,
      int identified,
      int unidentified,
      int remedyChosen,
      int resolved,
      Map<String, Integer> chosen) {}
}
