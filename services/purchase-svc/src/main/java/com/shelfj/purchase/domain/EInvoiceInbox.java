package com.shelfj.purchase.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Where a business fetches its invoices from (07.13): the networks that are asked rather than
 * delivered from.
 *
 * <p>Every other network on this platform delivers — an access point pushes what it received,
 * France's platform hands over what was deposited. <b>KSeF does neither.</b> A Polish buyer's
 * invoices sit in the ministry's system until the buyer asks for them, so a platform that only
 * listens receives nothing in Poland, however correct the rest of it is.
 */
public final class EInvoiceInbox {

  private EInvoiceInbox() {}

  public static final String NETWORK_NONE = "NONE";
  public static final String NETWORK_KSEF = "KSEF";
  public static final Set<String> NETWORKS = Set.of(NETWORK_NONE, NETWORK_KSEF);

  public static final String PROVIDER_NONE = "NONE";

  /** The platform standing in for the ministry: nothing leaves, and nothing arrives. */
  public static final String PROVIDER_SIMULATED = "SIMULATED";

  /** The ministry's own API. */
  public static final String PROVIDER_KSEF = "KSEF";

  public static final Set<String> PROVIDERS =
      Set.of(PROVIDER_NONE, PROVIDER_SIMULATED, PROVIDER_KSEF);

  /**
   * How far back a first fetch reaches when the business has never fetched.
   *
   * <p>Thirty days: enough to pick up the invoices a business is already late paying, and short
   * enough that turning the feature on does not pull a year of history into an inbox nobody has
   * time to work through. A business that wants the history asks for a window by hand.
   */
  public static final int FIRST_FETCH_DAYS = 30;

  /**
   * Where a business fetches from, and what it signs in with.
   *
   * @param providerAccount the business at the network: its NIP for KSeF
   * @param providerSecret the credential, sealed; never read back out over HTTP
   * @param fetchedTo the day the last fetch reached — the next asks from here, so nothing is missed
   *     and a repeat is harmless
   */
  public record Settings(
      UUID tenantId,
      String network,
      String provider,
      String providerAccount,
      String providerSecret,
      LocalDate fetchedTo,
      Instant lastFetchAt,
      String lastFetchNote,
      Instant updatedAt,
      UUID updatedBy) {

    public boolean fetching() {
      return !NETWORK_NONE.equals(network) && !PROVIDER_NONE.equals(provider);
    }

    public boolean hasSecret() {
      return providerSecret != null && !providerSecret.isBlank();
    }

    /** A business that has never fetched, so the settings screen has something to show. */
    public static Settings none(UUID tenantId) {
      return new Settings(
          tenantId, NETWORK_NONE, PROVIDER_NONE, null, null, null, null, null, null, null);
    }
  }

  /**
   * One invoice the network is holding for the business.
   *
   * @param reference the network's own number for it — a KSeF number, which is also how it is
   *     fetched
   */
  public record Waiting(String reference, LocalDate issueDate, String sellerVatId, String number) {}

  /**
   * What a fetch came to.
   *
   * @param received how many documents reached the inbox
   * @param alreadyHeld how many the inbox already had, which is the ordinary case on a repeat
   * @param refused how many the inbox would not take, with the reasons
   */
  public record Fetch(
      LocalDate from,
      LocalDate to,
      int waiting,
      int received,
      int alreadyHeld,
      int refused,
      java.util.List<String> notes) {

    public Fetch {
      notes = notes == null ? java.util.List.of() : java.util.List.copyOf(notes);
    }
  }
}
