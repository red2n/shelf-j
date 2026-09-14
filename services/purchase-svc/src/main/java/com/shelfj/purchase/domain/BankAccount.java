package com.shelfj.purchase.domain;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A supplier's bank account, validated the way a bank would refuse it (17.10).
 *
 * <p>Two shapes: a UK account (a six-digit sort code and an eight-digit account number), paid by
 * Faster Payments or BACS; and an international one (an IBAN, checked by its ISO 13616 mod-97 check
 * digits, with an optional BIC). A payment to a mistyped account is money that has to be chased
 * back from a stranger, so the check runs where the details are keyed rather than where the bank
 * rejects the file.
 */
public final class BankAccount {

  private BankAccount() {}

  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
  private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");
  private static final Pattern IBAN_SHAPE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
  private static final Pattern BIC_SHAPE =
      Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
  private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

  /**
   * @param raw a sort code as keyed: {@code 12-34-56}, {@code 12 34 56} or {@code 123456}
   * @return the six digits
   * @throws IllegalArgumentException when it is not six digits
   */
  public static String sortCode(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("[\\s-]", "");
    if (!SIX_DIGITS.matcher(digits).matches()) {
      throw new IllegalArgumentException("a sort code is six digits");
    }
    return digits;
  }

  /**
   * @param raw a UK account number as keyed, spaces allowed
   * @return the eight digits
   * @throws IllegalArgumentException when it is not eight digits
   */
  public static String accountNumber(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("\\s", "");
    if (!EIGHT_DIGITS.matcher(digits).matches()) {
      throw new IllegalArgumentException("a UK account number is eight digits");
    }
    return digits;
  }

  /**
   * @param raw an IBAN as keyed, spaces and lower case allowed
   * @return the IBAN in electronic form: upper case, no spaces
   * @throws IllegalArgumentException when its shape is wrong or its check digits do not verify
   */
  public static String iban(String raw) {
    String iban = raw == null ? "" : raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (!IBAN_SHAPE.matcher(iban).matches()) {
      throw new IllegalArgumentException(
          "an IBAN is a country code, two check digits and up to thirty letters or digits");
    }
    if (!ibanCheckDigitsVerify(iban)) {
      throw new IllegalArgumentException("the IBAN's check digits do not match — it is mistyped");
    }
    return iban;
  }

  /**
   * @param raw a BIC (SWIFT code) as keyed
   * @return upper case, eight or eleven characters
   * @throws IllegalArgumentException when its shape is wrong
   */
  public static String bic(String raw) {
    String bic = raw == null ? "" : raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (!BIC_SHAPE.matcher(bic).matches()) {
      throw new IllegalArgumentException("a BIC is eight or eleven letters and digits");
    }
    return bic;
  }

  /** ISO 13616: move the first four characters to the end, letters to numbers, mod 97 is 1. */
  static boolean ibanCheckDigitsVerify(String iban) {
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    StringBuilder digits = new StringBuilder(rearranged.length() * 2);
    for (char c : rearranged.toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      } else {
        digits.append(c - 'A' + 10);
      }
    }
    return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
  }

  /**
   * What a screen may show of an account number or IBAN: its last four characters.
   *
   * @param value the full value, or null
   * @return {@code ****1234}, or null
   */
  public static String masked(String value) {
    if (value == null || value.isBlank()) return null;
    return "****" + value.substring(Math.max(0, value.length() - 4));
  }

  /**
   * A supplier's full set of bank details, validated together: the account holder's name, and
   * either a UK sort code with an account number or an IBAN (with an optional BIC), or both.
   */
  public record Details(
      String accountName, String sortCode, String accountNumber, String iban, String bic) {

    /** No bank details at all. */
    public static final Details NONE = new Details(null, null, null, null, null);

    /** Whether nothing was given. */
    public boolean empty() {
      return accountName == null
          && sortCode == null
          && accountNumber == null
          && iban == null
          && bic == null;
    }

    /** Whether a payment can be made to it: a name and a UK account or an IBAN. */
    public boolean payable() {
      return accountName != null && ((sortCode != null && accountNumber != null) || iban != null);
    }
  }

  /**
   * Validates a set of bank details as keyed.
   *
   * @return the normalised details, or {@link Details#NONE} when every field is blank
   * @throws IllegalArgumentException naming what is wrong or missing
   */
  public static Details details(
      String accountName, String sortCode, String accountNumber, String iban, String bic) {
    String name = blankToNull(accountName);
    String sort = blankToNull(sortCode);
    String account = blankToNull(accountNumber);
    String ib = blankToNull(iban);
    String b = blankToNull(bic);
    if (name == null && sort == null && account == null && ib == null && b == null) {
      return Details.NONE;
    }
    if ((sort == null) != (account == null)) {
      throw new IllegalArgumentException("a sort code and an account number go together");
    }
    if (b != null && ib == null) {
      throw new IllegalArgumentException("a BIC goes with an IBAN");
    }
    if (sort == null && ib == null) {
      throw new IllegalArgumentException("give a sort code and account number, or an IBAN");
    }
    if (name == null) {
      throw new IllegalArgumentException(
          "the account holder's name, as the bank has it, is required");
    }
    if (name.length() > 140) {
      throw new IllegalArgumentException("the account holder's name is at most 140 characters");
    }
    return new Details(
        name,
        sort == null ? null : sortCode(sort),
        account == null ? null : accountNumber(account),
        ib == null ? null : iban(ib),
        b == null ? null : bic(b));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
