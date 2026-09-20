package com.storeql.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What a business owes each jurisdiction, and what it filed (statutory reporting). */
public final class StatutoryDtos {

  private StatutoryDtos() {}

  /**
   * One period of one return, with its date and state worked out.
   *
   * @param state NOT_DUE, DUE, OVERDUE or FILED — derived on every read from the return's frequency
   *     and its statutory offset, never stored, because a stored deadline goes stale the first time
   *     a rule changes
   * @param exportService where the export that answers this return lives, or null where the
   *     platform cannot produce it — a link, because the service that owns the data serves the
   *     bytes
   */
  @Schema(name = "StatutoryObligation")
  public record ObligationResponse(
      String returnCode,
      String name,
      @Schema(
              description =
                  "COUNTRY or REGIME — a regime's return reaches a member while it is one.")
          String scopeKind,
      String scope,
      String frequency,
      String periodStart,
      @Schema(description = "The day after the period's last; the period does not include it.")
          String periodEnd,
      String dueOn,
      String state,
      String citation,
      String exportService,
      String exportPath,
      StatutoryFilingResponse filing) {}

  /**
   * Evidence that a return went.
   *
   * @param reference the authority's receipt, or null where the authority gives none
   * @param payloadDigest SHA-256 of what was sent, so the filing can be proved against an export
   *     produced later
   * @param stands false for a filing a correction has replaced; both stay on the record
   */
  @Schema(name = "StatutoryFiling")
  public record StatutoryFilingResponse(
      String id,
      String returnCode,
      String periodStart,
      String periodEnd,
      String filedAt,
      String reference,
      @Schema(description = "HMRC_MTD, MANUAL or SIMULATED.") String provider,
      String payloadDigest,
      String supersedes,
      boolean stands,
      String note) {}

  /**
   * Records a filing.
   *
   * <p>The period is named rather than inferred, so filing for the wrong month is a mistake
   * somebody has to make on purpose.
   */
  @Schema(name = "StatutoryFilingRequest")
  public record FileRequest(
      @Schema(description = "The first day of the period filed for, e.g. 2026-09-01.")
          @NotBlank
          @Size(max = 10)
          String periodStart,
      @Schema(description = "The authority's receipt, where it gives one.") @Size(max = 200)
          String reference,
      @Schema(description = "HMRC_MTD, MANUAL or SIMULATED.") @NotBlank @Size(max = 20)
          String provider,
      @Schema(
              description =
                  "SHA-256 of what was sent, base64. Optional, and worth having: it proves this"
                      + " filing against an export produced later.")
          @Size(max = 64)
          String payloadDigest,
      @Schema(
              description =
                  "The filing this one corrects. Required to file a period twice — a correction names"
                      + " what it replaces, and both stay on the record.")
          java.util.UUID supersedes,
      @Size(max = 1000) String note) {}

  /** The calendar, and the part of it that needs attention. */
  @Schema(name = "StatutoryCalendar")
  public record CalendarResponse(
      String asOf, List<ObligationResponse> obligations, List<ObligationResponse> outstanding) {
    public CalendarResponse {
      obligations = obligations == null ? List.of() : List.copyOf(obligations);
      outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }
  }
}
