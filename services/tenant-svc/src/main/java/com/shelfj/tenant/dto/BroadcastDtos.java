package com.shelfj.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Notices from management to the shop floor (store operations & workforce). */
public final class BroadcastDtos {

  private BroadcastDtos() {}

  @Schema(
      name = "BroadcastRequest",
      description =
          "A notice to the shop floor. Never edited once published: withdraw it and publish again,"
              + " so what staff acknowledged is what they saw.")
  public record PublishRequest(
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 4000) String body,
      @Schema(description = "INFO, IMPORTANT or URGENT. Only URGENT wakes the store's devices.")
          @NotBlank
          String priority,
      @Schema(description = "One store, or omit for every open store.") String storeId,
      @Schema(
              description =
                  "A role or tier, as the staff assignments name them; omit for everybody.")
          @Size(max = 40)
          String role,
      @Schema(
              description =
                  "Whether staff must acknowledge it; the reach view then names who has not.")
          Boolean requiresAck,
      @Schema(
              description =
                  "When it stops being current, ISO-8601; omit to pin it until withdrawn.")
          @Size(max = 40)
          String expiresAt) {}

  @Schema(name = "Broadcast")
  public record BroadcastResponse(
      String id,
      String title,
      String body,
      String priority,
      String storeId,
      String role,
      boolean requiresAck,
      String publishedAt,
      String expiresAt,
      @Schema(description = "PUBLISHED or WITHDRAWN.") String status,
      String createdBy,
      String withdrawnAt,
      String withdrawnBy,
      String withdrawnReason,
      @Schema(
              description =
                  "When the caller acknowledged it; absent when they have not, or on a management read.")
          String acknowledgedAt) {}

  @Schema(name = "WithdrawBroadcastRequest")
  public record WithdrawRequest(@NotBlank @Size(max = 500) String reason) {}

  @Schema(name = "AcknowledgeBroadcastRequest")
  public record AckRequest(@NotBlank String storeId) {}

  @Schema(
      name = "BroadcastReach",
      description = "How far a notice reached at one store, and who has not acknowledged it.")
  public record ReachResponse(
      String storeId,
      int addressed,
      int acknowledged,
      @Schema(description = "The people it is addressed to who have not acknowledged it, named.")
          List<String> outstanding,
      boolean complete) {

    public ReachResponse {
      outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }
  }
}
