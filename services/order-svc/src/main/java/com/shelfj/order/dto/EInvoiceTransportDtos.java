package com.shelfj.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** DTOs for where a business's e-invoices leave, and each attempt to send one. */
public final class EInvoiceTransportDtos {

  private EInvoiceTransportDtos() {}

  @Schema(
      name = "EInvoiceTransportSettings",
      description =
          "The network a business sends its e-invoices on, and what this deployment offers.")
  public record TransportSettingsResponse(
      @Schema(enumeration = {"NONE", "PEPPOL", "FR_PDP", "KSEF", "IRP"}) String network,
      @Schema(description = "SIMULATED, or a real provider's name; null with NONE.")
          String provider,
      String providerAccount,
      @Schema(description = "Whether a credential is kept for the provider; never shown.")
          boolean hasSecret,
      String updatedAt,
      @Schema(description = "The business's own electronic address, scheme:identifier, or null.")
          String senderAddress,
      @Schema(description = "The network the business's country asks for today, or null.")
          String suggestedNetwork,
      List<String> networks,
      @Schema(description = "The providers deployed, by network.")
          Map<String, List<String>> providers,
      @Schema(description = "The providers that can be chosen here, by network: configured.")
          Map<String, List<String>> available,
      @Schema(description = "The providers that take the business's own credential, by network.")
          Map<String, List<String>> needingSecret) {}

  @Schema(name = "SetEInvoiceTransportRequest")
  public record SetTransportRequest(
      @Schema(enumeration = {"NONE", "PEPPOL", "FR_PDP", "KSEF", "IRP"}) @NotBlank String network,
      @Schema(description = "Required unless NONE.") @Size(max = 40) String provider,
      @Schema(
              description =
                  "The business at the provider: a legal-entity id, a NIP, a portal user.")
          @Size(max = 120)
          String providerAccount,
      @Schema(
              description =
                  "The business's own credential at the provider — a portal password, a token."
                      + " Kept sealed and never shown; leave out to keep the one stored, blank to"
                      + " remove it.")
          @Size(max = 200)
          String providerSecret) {}

  @Schema(
      name = "EInvoiceTransmission",
      description = "One attempt to send a document, and what the network said.")
  public record TransmissionResponse(
      String id,
      String invoiceId,
      String network,
      String provider,
      @Schema(enumeration = {"QUEUED", "SENDING", "PENDING", "ACCEPTED", "REJECTED", "FAILED"})
          String status,
      int attempts,
      String nextAttemptAt,
      @Schema(description = "scheme:identifier the document went to, when the network addresses.")
          String receiver,
      @Schema(description = "The network's reference: a message id, a KSeF number, an IRN.")
          String providerRef,
      @Schema(description = "The last outcome, in words.") String detail,
      String createdAt,
      String sentAt,
      String settledAt) {}
}
