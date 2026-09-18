package com.shelfj.tenant.domain;

import java.util.Locale;

/**
 * Which VAT treatment the platform's own invoice carries (21.9).
 *
 * <p>Four cases, not three. The one that is easy to miss is the third: a business in another EU
 * state that has <em>no</em> valid VAT number is not a reverse charge — it is taxed at its own
 * country's rate. Treating it as a reverse charge issues an invoice with no VAT that should have
 * carried some, and the platform, not the customer, owes the difference.
 *
 * <ul>
 *   <li><b>DOMESTIC</b> — the buyer is where the platform is established: the platform's own rate.
 *   <li><b>REVERSE_CHARGE</b> — another EU member state, and the buyer's VAT number has been
 *       checked: no VAT, and the invoice must carry both VAT numbers and the words "Reverse charge"
 *       (Directive 2006/112/EC art. 226(11a), required across the EU since 1 January 2013).
 *   <li><b>DESTINATION</b> — another EU member state with no checked VAT number: that country's
 *       rate. The rate comes from the platform's own table, and an invoice is <em>refused</em>
 *       rather than issued when the country has no rate in it.
 *   <li><b>OUT_OF_SCOPE</b> — outside the EU.
 * </ul>
 *
 * <p><b>Membership is asked of a date, not of a list.</b> The platform already records which
 * countries belong to which regime and when — the United Kingdom was a member until 31 January 2020
 * — so whether a country was in the EU is a question about the invoice's date, and the caller
 * answers it from that data rather than from a constant here.
 *
 * <p><b>When the platform is not established in the EU</b> none of the EU cases arise: a sale in
 * its own country carries its own rate, and everything else is outside the scope of the VAT it
 * charges. Registering elsewhere is a commercial decision nobody has taken, and guessing at it here
 * would be inventing a tax position.
 *
 * <p>A pure function over a handful of facts. Every case below is a test that needs no database.
 */
public final class BillingTax {

  private BillingTax() {}

  public static final String DOMESTIC = "DOMESTIC";
  public static final String REVERSE_CHARGE = "REVERSE_CHARGE";
  public static final String DESTINATION = "DESTINATION";
  public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";

  /** The wording the directive requires on a reverse-charge invoice, in as many words. */
  public static final String REVERSE_CHARGE_WORDING =
      "Reverse charge — VAT to be accounted for by the recipient under Article 196 of Directive"
          + " 2006/112/EC";

  /**
   * A treatment, and why it was chosen — the reason is printed on the invoice or shown to whoever
   * has to explain it.
   *
   * @param needsRate whether an invoice under this treatment carries VAT at all
   */
  public record Treatment(String code, String reason, boolean needsRate) {}

  /**
   * Decides the treatment.
   *
   * @param sellerCountry where the platform is established, ISO 3166-1 alpha-2
   * @param sellerInEu whether that country was an EU member on the invoice's date
   * @param buyerCountry where the business is
   * @param buyerInEu whether the buyer's country was an EU member on that date
   * @param buyerVatNumber the buyer's VAT number, or null
   * @param vatNumberChecked whether that number has been verified and the check recorded; an
   *     unchecked number is treated as no number, because the reverse charge rests on it being real
   * @return the treatment, never null
   */
  public static Treatment decide(
      String sellerCountry,
      boolean sellerInEu,
      String buyerCountry,
      boolean buyerInEu,
      String buyerVatNumber,
      boolean vatNumberChecked) {

    String seller = upper(sellerCountry);
    String buyer = upper(buyerCountry);

    if (!seller.isEmpty() && seller.equals(buyer)) {
      return new Treatment(DOMESTIC, "the business is in the platform's own country", true);
    }
    if (!sellerInEu) {
      return new Treatment(
          OUT_OF_SCOPE,
          "the platform is not established in the EU, so its VAT does not reach this sale",
          false);
    }
    if (!buyerInEu) {
      return new Treatment(OUT_OF_SCOPE, "the business is outside the EU", false);
    }
    if (hasNumber(buyerVatNumber) && vatNumberChecked) {
      return new Treatment(
          REVERSE_CHARGE,
          "the business is VAT-registered in another member state, and its number has been checked",
          false);
    }
    return new Treatment(
        DESTINATION,
        hasNumber(buyerVatNumber)
            ? "the business gave a VAT number that has not been checked, so it is charged its own"
                + " country's rate until it is"
            : "the business is in another member state and gave no VAT number, so it is charged its"
                + " own country's rate",
        true);
  }

  /** Which country's rate a treatment charges, or null when it charges none. */
  public static String rateCountry(Treatment treatment, String sellerCountry, String buyerCountry) {
    if (!treatment.needsRate()) return null;
    return DOMESTIC.equals(treatment.code()) ? upper(sellerCountry) : upper(buyerCountry);
  }

  private static boolean hasNumber(String vat) {
    return vat != null && !vat.isBlank();
  }

  private static String upper(String country) {
    return country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
  }
}
