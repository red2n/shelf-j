package com.shelfj.tenant.dto;

import jakarta.json.JsonObject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes of a business leaving (21.14). */
public final class SwitchingDtos {

  private SwitchingDtos() {}

  /** Notice to switch or to have the data erased. */
  public record GiveNoticeRequest(
      @Schema(description = "SWITCH to take the data elsewhere, ERASE to have it erased.") @NotBlank
          String intent,
      @Schema(description = "The last day of notice, YYYY-MM-DD: today to two months ahead.")
          @NotBlank
          String noticeEndsOn) {}

  /** The one extension of the transitional period. */
  public record ExtendRequest(
      @Schema(description = "The new last day of the transitional period, YYYY-MM-DD.") @NotBlank
          String transitionEndsOn) {}

  /** Withdrawing the notice while it runs. */
  public record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

  /** A notice as recorded. */
  public record NoticeResponse(
      String id,
      String intent,
      String noticeGivenAt,
      String noticeGivenBy,
      String noticeEndsOn,
      String transitionEndsOn,
      String extendedAt,
      String retrievalEndsOn,
      String erasureDueOn,
      String cancelledAt,
      String cancelReason,
      String erasureStartedAt) {}

  /** What one service erased. */
  public record EvidenceResponse(
      String service, int rowsErased, String erasedAt, String recordedAt, JsonObject tables) {}

  /**
   * Where a business's leaving stands.
   *
   * @param stage NOTICE, TRANSITION, RETRIEVAL, ERASURE_DUE, ERASING, ERASED or CANCELLED
   * @param awaiting the services whose evidence has not arrived, once erasure has started
   */
  public record StatusResponse(
      NoticeResponse notice,
      String stage,
      List<EvidenceResponse> evidence,
      List<String> services,
      List<String> awaiting) {}

  /** How many erasures a sweep started. */
  public record SweepResponse(int started) {}
}
