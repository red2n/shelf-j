package com.shelfj.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Where a business's e-invoices leave, and each attempt to send one (the transport seam). */
public final class EInvoiceTransports {

  /** No network: documents are issued and downloaded, never sent. */
  public static final String NETWORK_NONE = "NONE";

  /** Peppol, through an access point: Belgium now, Germany from 2027, the UK from 2029. */
  public static final String NETWORK_PEPPOL = "PEPPOL";

  /** France's approved platform (plateforme de dématérialisation partenaire). */
  public static final String NETWORK_FR_PDP = "FR_PDP";

  /** Poland's Krajowy System e-Faktur. */
  public static final String NETWORK_KSEF = "KSEF";

  /** India's Invoice Registration Portal. */
  public static final String NETWORK_IRP = "IRP";

  /** The networks a business can send on, in the order the mandates arrive. */
  public static final List<String> NETWORKS =
      List.of(NETWORK_PEPPOL, NETWORK_FR_PDP, NETWORK_KSEF, NETWORK_IRP);

  /**
   * The networks that address a document to a receiver; the others take it from the sender alone.
   */
  public static final Set<String> ADDRESSED = Set.of(NETWORK_PEPPOL, NETWORK_FR_PDP);

  /** The platform standing in for the network: what a stack with no provider contract sends on. */
  public static final String PROVIDER_SIMULATED = "SIMULATED";

  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_SENDING = "SENDING";
  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_REJECTED = "REJECTED";
  public static final String STATUS_FAILED = "FAILED";

  public static final List<String> STATUSES =
      List.of(
          STATUS_QUEUED,
          STATUS_SENDING,
          STATUS_PENDING,
          STATUS_ACCEPTED,
          STATUS_REJECTED,
          STATUS_FAILED);

  private EInvoiceTransports() {}

  /** A business's choice of network and provider. */
  public record Settings(
      UUID tenantId,
      String network,
      String provider,
      String providerAccount,
      Instant updatedAt,
      UUID updatedBy) {

    public static Settings none(UUID tenantId) {
      return new Settings(tenantId, NETWORK_NONE, null, null, null, null);
    }

    /** Whether documents leave at all. */
    public boolean sending() {
      return !NETWORK_NONE.equals(network);
    }
  }

  /**
   * One attempt to send a document, and what the network said.
   *
   * @param receiver the electronic address the document went to, when the network needs one
   * @param providerRef the network's reference once it has one
   * @param response the network's last answer, as JSON
   */
  public record Transmission(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      String network,
      String provider,
      String status,
      int attempts,
      Instant nextAttemptAt,
      String receiver,
      String providerRef,
      String detail,
      String response,
      Instant createdAt,
      Instant updatedAt,
      Instant sentAt,
      Instant settledAt,
      UUID createdBy) {

    /** Still in the network's hands: not yet answered for good. */
    public boolean open() {
      return STATUS_QUEUED.equals(status)
          || STATUS_SENDING.equals(status)
          || STATUS_PENDING.equals(status);
    }

    /** Answered for good, one way or the other. */
    public boolean settled() {
      return STATUS_ACCEPTED.equals(status)
          || STATUS_REJECTED.equals(status)
          || STATUS_FAILED.equals(status);
    }
  }

  /**
   * How long to wait before trying again after the given number of failed attempts: the base
   * doubled each time, never more than an hour; empty once the attempts are spent.
   */
  public static java.util.Optional<Duration> backoff(int attempts, Duration base, int maxAttempts) {
    if (attempts >= maxAttempts) return java.util.Optional.empty();
    long factor = 1L << Math.min(attempts - 1, 20);
    Duration wait = base.multipliedBy(Math.max(1, factor));
    return java.util.Optional.of(
        wait.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : wait);
  }
}
