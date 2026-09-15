package com.shelfj.order.domain;

import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A recall's notice to one buyer (GPSR arts.35–37): issued to the person an order identifies, the
 * remedy they chose from what the recall offered, and how it was settled.
 */
public final class RecallNotice {

  private RecallNotice() {}

  public enum Status {
    /** Someone to tell, and told. */
    ISSUED,
    /** An anonymous till sale: nobody to tell, kept for the count and for whoever comes back. */
    UNIDENTIFIED,
    REMEDY_CHOSEN,
    RESOLVED
  }

  /** What the recall offered, GPSR art.37; the buyer chooses among them. */
  public enum Remedy {
    REPAIR,
    REPLACEMENT,
    REFUND;

    /** The stored form: names in declaration order, comma-separated. */
    public static String csv(Set<Remedy> remedies) {
      return EnumSet.copyOf(remedies).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * Parses the stored or the wire form.
     *
     * @return the remedies, empty for null or blank
     */
    public static Set<Remedy> parse(String csv) {
      if (csv == null || csv.isBlank()) return Set.of();
      Set<Remedy> out = EnumSet.noneOf(Remedy.class);
      for (String name : csv.split(",")) {
        out.add(valueOf(name.trim()));
      }
      return Set.copyOf(out);
    }
  }

  /** How a notice was settled. */
  public enum Resolution {
    REFUNDED,
    REPLACED,
    REPAIRED,
    /** The buyer was reached and wanted nothing. */
    DECLINED
  }

  /** Who chose the remedy: the buyer on the storefront, or staff for a buyer at the counter. */
  public enum ChosenVia {
    SHOPPER,
    STAFF
  }

  /** The remedy the buyer chose, GPSR art.37, and who recorded it. */
  public record Choice(Remedy remedy, Instant at, UUID by, ChosenVia via) {}

  /** How the notice was settled. */
  public record Settlement(
      Resolution resolution, Instant at, UUID by, UUID returnId, String notes) {}

  public record Notice(
      UUID id,
      UUID tenantId,
      UUID recallId,
      String reference,
      String hazard,
      String reason,
      String customerNotice,
      Set<Remedy> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      UUID loginId,
      String buyerPhone,
      /** Whether the order named anyone to write to, fixed when the notice was issued. */
      boolean buyerIdentified,
      Instant soldAt,
      Status status,
      Instant issuedAt,
      /** Null until the buyer chooses. */
      Choice choice,
      /** Null until settled. */
      Settlement settlement) {

    /**
     * Whether the order names anyone this notice can reach: a login, the shop's customer record, or
     * a phone number left at checkout.
     */
    public static boolean identifies(UUID customerId, UUID loginId, String buyerPhone) {
      return customerId != null || loginId != null || (buyerPhone != null && !buyerPhone.isBlank());
    }

    /** Whether this login placed the order the notice is about. */
    public boolean belongsTo(UUID login) {
      return login != null && login.equals(loginId);
    }

    /**
     * Refuses a remedy the notice cannot take.
     *
     * @throws ApiException 409 {@code RECALL_NOTICE_RESOLVED} once settled; {@code
     *     RECALL_REMEDY_ALREADY_CHOSEN} for a second choice; {@code RECALL_REMEDY_NOT_OFFERED} for
     *     one the recall did not offer
     */
    public void requireCanChoose(Remedy wanted) {
      requireOpen();
      if (choice != null) {
        throw ApiException.conflict(
            "RECALL_REMEDY_ALREADY_CHOSEN", "A remedy was already chosen: " + choice.remedy());
      }
      if (!remedies.contains(wanted)) {
        throw ApiException.conflict(
            "RECALL_REMEDY_NOT_OFFERED",
            "This recall offers " + Remedy.csv(remedies).replace(",", ", ") + ", not " + wanted);
      }
    }

    /**
     * @throws ApiException 409 {@code RECALL_NOTICE_RESOLVED} once settled
     */
    public void requireOpen() {
      if (status == Status.RESOLVED) {
        throw ApiException.conflict(
            "RECALL_NOTICE_RESOLVED",
            "This notice was settled: " + (settlement == null ? "" : settlement.resolution()));
      }
    }
  }

  /** One line of the order the recall covers, as inventory-svc matched it. */
  public record Line(
      UUID id,
      UUID variantId,
      String productName,
      String sku,
      String batchNo,
      LocalDate expiryDate,
      BigDecimal qty,
      String match) {}

  public record Detail(Notice notice, List<Line> lines) {}

  /** How a recall's buyers stand: how many could be told, and how far each has got. */
  public record Progress(
      int notices,
      int identified,
      int unidentified,
      int remedyChosen,
      int resolved,
      Map<Remedy, Integer> chosen) {}
}
