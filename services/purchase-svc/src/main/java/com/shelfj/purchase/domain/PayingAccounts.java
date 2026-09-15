package com.shelfj.purchase.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The business's own accounts that supplier payments are paid from (17.12): one per currency, kept
 * as history. A bank file names the account it debits, and a sterling Bacs file the business's
 * service user number, so neither file can be written without one.
 */
public final class PayingAccounts {

  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  private PayingAccounts() {}

  /**
   * A paying account as set.
   *
   * @param serviceUserNumber the six-digit Bacs SUN, for a sterling account that sends Bacs files
   */
  public record PayingAccount(
      UUID id,
      UUID tenantId,
      String currency,
      String accountName,
      String sortCode,
      String accountNumber,
      String iban,
      String bic,
      String serviceUserNumber,
      UUID setBy,
      Instant setAt) {

    /** Whether a Bacs file can be sent from it. */
    public boolean sendsBacs() {
      return sortCode != null && accountNumber != null && serviceUserNumber != null;
    }

    /** Whether a SEPA credit transfer can be sent from it. */
    public boolean sendsSepa() {
      return iban != null;
    }
  }

  /**
   * @param raw a Bacs service user number as keyed, or blank
   * @return the six digits, or null when none was given
   * @throws IllegalArgumentException when one was given and it is not six digits
   */
  public static String serviceUserNumber(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String digits = raw.replaceAll("\\s", "");
    if (!SIX_DIGITS.matcher(digits).matches()) {
      throw new IllegalArgumentException("a Bacs service user number is six digits");
    }
    return digits;
  }
}
