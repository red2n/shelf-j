package com.storeql.purchase.domain;

import com.storeql.einvoice.ElectronicAddress;
import com.storeql.einvoice.Invoice;
import com.storeql.einvoice.VatIdentifier;
import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a received supplier e-invoice becomes (07.13): who sent it, whether it is addressed to this
 * business, which purchase order it bills and which of the order's lines each of its lines is.
 *
 * <p>Nothing here guesses. A supplier is the one whose electronic address the invoice was sent
 * from, or failing that the one with the seller's VAT identifier, and two suppliers answering
 * either is no answer. An order is the one the invoice names by its id. A line is the order line
 * the supplier referenced, or the one whose variant the supplier's item code was matched to before
 * by a person. What cannot be decided this way is left for a person, with the reason.
 */
public final class EInvoiceIntake {

  public static final String STATUS_NOT_COMPLIANT = "NOT_COMPLIANT";
  public static final String STATUS_MISDIRECTED = "MISDIRECTED";
  public static final String STATUS_NEEDS_SUPPLIER = "NEEDS_SUPPLIER";
  public static final String STATUS_NEEDS_ORDER = "NEEDS_ORDER";
  public static final String STATUS_NEEDS_LINES = "NEEDS_LINES";
  public static final String STATUS_NEEDS_RETURN = "NEEDS_RETURN";
  public static final String STATUS_NEEDS_DECISION = "NEEDS_DECISION";
  public static final String STATUS_DUPLICATE = "DUPLICATE";
  public static final String STATUS_CAPTURED = "CAPTURED";
  public static final String STATUS_CREDITED = "CREDITED";
  public static final String STATUS_REFUSED = "REFUSED";

  /** Statuses a person can still act on; the others are settled. */
  public static final Set<String> OPEN =
      Set.of(
          STATUS_NOT_COMPLIANT,
          STATUS_MISDIRECTED,
          STATUS_NEEDS_SUPPLIER,
          STATUS_NEEDS_ORDER,
          STATUS_NEEDS_LINES,
          STATUS_NEEDS_RETURN,
          STATUS_NEEDS_DECISION);

  public static final String MATCHED_BY_ORDER_LINE = "ORDER_LINE";
  public static final String MATCHED_BY_ITEM_CODE = "ITEM_CODE";
  public static final String MATCHED_BY_PERSON = "PERSON";

  public static final String CODE_SELLER = "SELLER";
  public static final String CODE_BUYER = "BUYER";
  public static final String CODE_STANDARD = "STANDARD";

  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private EInvoiceIntake() {}

  /** A supplier as intake compares it. */
  public record SupplierRef(
      UUID id, String name, String vatNumber, String einvoiceScheme, String einvoiceId) {

    ElectronicAddress address() {
      return einvoiceId == null ? null : new ElectronicAddress(einvoiceScheme, einvoiceId);
    }
  }

  /** A line of the purchase order, numbered from 1 in the order it was added. */
  public record OrderLine(UUID id, int position, UUID variantId) {}

  /** What one of a supplier's own item codes was matched to by a person. */
  public record ItemCode(String kind, String code, UUID variantId) {}

  /** The order line an invoice line is, when it could be told. */
  public record LineMatch(int position, UUID poLineId, UUID variantId, String matchedBy) {

    public boolean matched() {
      return poLineId != null;
    }
  }

  /** A decision, or the reason a person has to make it. */
  public record Found<T>(T value, String problem) {

    static <T> Found<T> of(T value) {
      return new Found<>(value, null);
    }

    static <T> Found<T> missing(String problem) {
      return new Found<>(null, problem);
    }
  }

  /**
   * Why the invoice is addressed to another business, or {@code null} when it is addressed to this
   * one or cannot be told apart: a business that has set neither its VAT identifier nor its
   * electronic address, or an invoice that gives neither of the ones it has set, is not refused on
   * a comparison that was never made.
   */
  public static String misdirected(Invoice inv, String ownVatNumber, ElectronicAddress ownAddress) {
    Invoice.Party buyer = inv.buyer();
    ElectronicAddress buyerAddress =
        buyer == null ? null : ElectronicAddress.of(buyer.electronicAddress());
    String buyerVat = buyer == null ? null : VatIdentifier.normalise(buyer.vatId());
    boolean addressComparable = ownAddress != null && buyerAddress != null;
    boolean vatComparable = ownVatNumber != null && buyerVat != null;
    if (addressComparable && ownAddress.sameParticipant(buyerAddress)) return null;
    if (vatComparable && VatIdentifier.same(ownVatNumber, buyerVat)) return null;
    if (!addressComparable && !vatComparable) return null;
    List<String> named = new ArrayList<>();
    if (buyer != null && buyer.name() != null) named.add(buyer.name());
    if (buyerVat != null) named.add("VAT " + buyerVat);
    if (buyerAddress != null) named.add("address " + buyerAddress);
    return "addressed to " + String.join(", ", named) + ", not to this business";
  }

  /** The supplier that sent the invoice. */
  public static Found<SupplierRef> supplier(Invoice inv, List<SupplierRef> suppliers) {
    Invoice.Party seller = inv.seller();
    ElectronicAddress from =
        seller == null ? null : ElectronicAddress.of(seller.electronicAddress());
    if (from != null) {
      List<SupplierRef> byAddress =
          suppliers.stream().filter(s -> from.sameParticipant(s.address())).toList();
      if (byAddress.size() == 1) return Found.of(byAddress.get(0));
    }
    String vat = seller == null ? null : VatIdentifier.normalise(seller.vatId());
    if (vat == null && inv.taxRepresentative() != null) {
      vat = VatIdentifier.normalise(inv.taxRepresentative().vatId());
    }
    if (vat != null) {
      String wanted = vat;
      List<SupplierRef> byVat =
          suppliers.stream().filter(s -> VatIdentifier.same(s.vatNumber(), wanted)).toList();
      if (byVat.size() == 1) return Found.of(byVat.get(0));
      if (byVat.size() > 1) {
        return Found.missing(
            byVat.size()
                + " suppliers have the VAT identifier "
                + vat
                + "; choose the one that sent it");
      }
    }
    String name = seller == null || seller.name() == null ? "the seller" : seller.name();
    return Found.missing(
        "no supplier sends from "
            + (from == null ? "no electronic address" : from.toString())
            + " or has the VAT identifier "
            + (vat == null ? "(none given)" : vat)
            + "; add "
            + name
            + " or give an existing supplier that address");
  }

  /** The purchase order id the invoice names in its order reference (BT-13), if it names one. */
  public static Optional<UUID> orderReference(Invoice inv) {
    String ref = inv.orderReference();
    if (ref == null) return Optional.empty();
    Matcher m = UUID_TEXT.matcher(ref);
    return m.find() ? Optional.of(Ids.parse(m.group())) : Optional.empty();
  }

  /**
   * The order line each invoice line is. An order line is taken once: two invoice lines for the
   * same variant take the order's two lines for it in order, and a third waits for a person.
   */
  public static List<LineMatch> lines(
      Invoice inv, List<OrderLine> orderLines, List<ItemCode> codes) {
    List<LineMatch> out = new ArrayList<>();
    Set<UUID> taken = new HashSet<>();
    List<Invoice.Line> lines = inv.lines();
    for (int i = 0; i < lines.size(); i++) {
      Invoice.Line line = lines.get(i);
      int position = i + 1;
      OrderLine byReference = byOrderLineReference(line.orderLineReference(), orderLines, taken);
      if (byReference != null) {
        taken.add(byReference.id());
        out.add(
            new LineMatch(
                position, byReference.id(), byReference.variantId(), MATCHED_BY_ORDER_LINE));
        continue;
      }
      UUID variant = variantByCode(line, codes, orderLines);
      OrderLine byVariant = variant == null ? null : firstUntaken(variant, orderLines, taken);
      if (byVariant != null) {
        taken.add(byVariant.id());
        out.add(new LineMatch(position, byVariant.id(), variant, MATCHED_BY_ITEM_CODE));
      } else {
        out.add(new LineMatch(position, null, null, null));
      }
    }
    return out;
  }

  private static OrderLine byOrderLineReference(
      String reference, List<OrderLine> orderLines, Set<UUID> taken) {
    if (reference == null || reference.isBlank()) return null;
    String ref = reference.strip();
    Matcher m = UUID_TEXT.matcher(ref);
    if (m.matches()) {
      UUID id = Ids.parse(ref);
      return orderLines.stream()
          .filter(o -> o.id().equals(id) && !taken.contains(o.id()))
          .findFirst()
          .orElse(null);
    }
    if (ref.matches("\\d{1,4}")) {
      int n = Integer.parseInt(ref);
      return orderLines.stream()
          .filter(o -> o.position() == n && !taken.contains(o.id()))
          .findFirst()
          .orElse(null);
    }
    return null;
  }

  private static UUID variantByCode(
      Invoice.Line line, List<ItemCode> codes, List<OrderLine> orderLines) {
    Invoice.Item item = line.item();
    if (item == null) return null;
    for (ItemCode c : codes) {
      String code =
          switch (c.kind()) {
            case CODE_SELLER -> item.sellersId();
            case CODE_BUYER -> item.buyersId();
            case CODE_STANDARD -> standardCode(item);
            default -> null;
          };
      if (code != null && code.strip().equalsIgnoreCase(c.code())) return c.variantId();
    }
    // A buyer's item identifier that is one of the order's own variants is ours already.
    if (item.buyersId() != null && UUID_TEXT.matcher(item.buyersId().strip()).matches()) {
      UUID id = Ids.parse(item.buyersId().strip());
      if (orderLines.stream().anyMatch(o -> o.variantId().equals(id))) return id;
    }
    return null;
  }

  /** An item's standard identifier as a code is kept: {@code scheme:identifier}. */
  public static String standardCode(Invoice.Item item) {
    if (item == null || item.standardId() == null || item.standardId().id() == null) return null;
    String scheme = item.standardId().scheme() == null ? "" : item.standardId().scheme().strip();
    return (scheme + ":" + item.standardId().id().strip()).toUpperCase(Locale.ROOT);
  }

  private static OrderLine firstUntaken(UUID variant, List<OrderLine> orderLines, Set<UUID> taken) {
    return orderLines.stream()
        .filter(o -> o.variantId().equals(variant) && !taken.contains(o.id()))
        .findFirst()
        .orElse(null);
  }

  /**
   * What one invoice line charges for one unit: its net amount over its quantity, which carries the
   * line's own allowances and charges and the price's base quantity, so it is compared with the
   * order's unit price like for like.
   */
  public static BigDecimal unitPrice(Invoice.Line line) {
    if (line.netAmount() == null) return null;
    if (line.quantity() == null || line.quantity().signum() == 0) {
      return line.price() == null ? null : line.price().net();
    }
    return line.netAmount().divide(line.quantity(), 6, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  /** A vendor return a credit note can close, as intake compares it. */
  public record ReturnRef(UUID id, String status, BigDecimal grossAmount) {}

  /**
   * The return a credit note closes: the only open one on the order, or of several the only one
   * whose gross is what the credit note credits.
   */
  public static Found<ReturnRef> creditedReturn(Invoice inv, List<ReturnRef> returns) {
    List<ReturnRef> open = returns.stream().filter(r -> "RAISED".equals(r.status())).toList();
    if (open.size() == 1) return Found.of(open.get(0));
    if (open.isEmpty()) {
      return Found.missing("no return on that order is waiting for a credit note");
    }
    BigDecimal credited = inv.totals() == null ? null : inv.totals().withVat();
    List<ReturnRef> same =
        credited == null
            ? List.of()
            : open.stream()
                .filter(r -> r.grossAmount() != null && r.grossAmount().compareTo(credited) == 0)
                .toList();
    if (same.size() == 1) return Found.of(same.get(0));
    return Found.missing(
        open.size()
            + " returns on that order are waiting for a credit note; choose the one it closes");
  }
}
