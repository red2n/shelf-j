package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The file a payment run hands the bank (17.10): one payment per supplier, in the columns a bank's
 * bulk-payment upload takes — payee, sort code and account or IBAN and BIC, amount, currency and
 * the reference the supplier sees on their statement.
 *
 * <p>The file is opened in a spreadsheet as often as it is uploaded, so every text cell is guarded
 * against formula injection: a supplier named {@code =HYPERLINK(...)} is written as text, not run.
 */
public final class BankFile {

  private BankFile() {}

  /** The header row. */
  public static final String HEADER =
      "payee_name,sort_code,account_number,iban,bic,amount,currency,reference";

  /**
   * One payment.
   *
   * @param reference what the supplier's statement shows; at most 18 characters for BACS
   */
  public record Payment(
      String payeeName,
      String sortCode,
      String accountNumber,
      String iban,
      String bic,
      BigDecimal amount,
      String currency,
      String reference) {}

  /**
   * Writes the file.
   *
   * @param payments one row each, in order
   * @return CSV text with a header row and CRLF line endings, as bank upload formats expect
   */
  public static String csv(List<Payment> payments) {
    StringBuilder out = new StringBuilder(HEADER).append("\r\n");
    for (Payment p : payments) {
      out.append(cell(p.payeeName()))
          .append(',')
          .append(cell(p.sortCode()))
          .append(',')
          .append(cell(p.accountNumber()))
          .append(',')
          .append(cell(p.iban()))
          .append(',')
          .append(cell(p.bic()))
          .append(',')
          .append(p.amount().toPlainString())
          .append(',')
          .append(cell(p.currency()))
          .append(',')
          .append(cell(p.reference()))
          .append("\r\n");
    }
    return out.toString();
  }

  /**
   * One text cell: empty for null; a leading formula character neutralised with a quote; quoted
   * when it holds a comma, a quote or a line break, with inner quotes doubled.
   */
  static String cell(String value) {
    if (value == null) return "";
    String v = value.replace("\r", " ").replace("\n", " ");
    if (!v.isEmpty() && "=+-@\t".indexOf(v.charAt(0)) >= 0) v = "'" + v;
    if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0) {
      v = '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }

  /** The file's name: the run reference, lower-cased, safe for a download. */
  public static String fileName(String runReference) {
    return runReference.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "") + ".csv";
  }
}
