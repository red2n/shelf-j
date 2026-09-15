package com.shelfj.einvoice;

import com.shelfj.einvoice.Invoice.Identifier;

/**
 * A Peppol participant identifier: the electronic address scheme (EAS) and the identifier within
 * it, as an invoice's seller (BT-34) and buyer (BT-49) carry it, and as a business registers where
 * its invoices come from and go to.
 *
 * @param scheme the EAS code, e.g. {@code 0088} for a GLN or {@code 9930} for a German VAT number
 * @param id the identifier within that scheme
 */
public record ElectronicAddress(String scheme, String id) {

  private static final int MAX_ID = 128;

  /**
   * An address from what a person entered, or {@code null} when both halves are empty. An
   * identifier whose scheme Peppol checks the format of (a GLN's check digit, a Belgian enterprise
   * number's) is checked the same way here, so a mistyped one is refused where it was typed rather
   * than when an access point cannot route to it.
   *
   * @throws IllegalArgumentException when only one half is given, the scheme is not one Peppol
   *     routes on, or the identifier is not up to 128 printable characters without spaces or fails
   *     its scheme's check
   */
  public static ElectronicAddress parse(String scheme, String id) {
    String s = scheme == null ? "" : scheme.strip();
    String i = id == null ? "" : id.strip();
    if (s.isEmpty() && i.isEmpty()) return null;
    if (s.isEmpty() || i.isEmpty()) {
      throw new IllegalArgumentException(
          "an electronic address needs both its scheme and its identifier");
    }
    if (!Codes.contains(Codes.CodeList.PEPPOL_EAS, s)) {
      throw new IllegalArgumentException(
          "\""
              + (s.length() > 12 ? s.substring(0, 12) + "…" : s)
              + "\" is not an electronic"
              + " address scheme Peppol routes on");
    }
    if (i.length() > MAX_ID || !i.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
      throw new IllegalArgumentException(
          "an electronic address identifier is up to 128 printable characters without spaces");
    }
    boolean valid =
        switch (s) {
          case "0088" -> Rules.gln(i);
          case "0208" -> Rules.belgianEnterprise(i);
          case "0192" -> Rules.norwegianOrganisation(i);
          case "0007" -> Rules.swedishOrganisation(i);
          case "0151" -> Rules.australianBusinessNumber(i);
          default -> true;
        };
    if (!valid) {
      throw new IllegalArgumentException(
          i + " is not a valid identifier in scheme " + s + ": its check digits do not agree");
    }
    return new ElectronicAddress(s, i);
  }

  /** The address an invoice gives, or {@code null} when it gives none or no scheme with it. */
  public static ElectronicAddress of(Identifier identifier) {
    if (identifier == null || identifier.id() == null || identifier.scheme() == null) return null;
    return new ElectronicAddress(identifier.scheme().strip(), identifier.id().strip());
  }

  /**
   * Whether this is the same participant: the same scheme, and the identifier without regard to
   * case, as the Peppol directory compares them.
   */
  public boolean sameParticipant(ElectronicAddress other) {
    return other != null && scheme.equals(other.scheme) && id.equalsIgnoreCase(other.id);
  }

  /** As a document or a log names it: {@code scheme:identifier}. */
  @Override
  public String toString() {
    return scheme + ":" + id;
  }
}
