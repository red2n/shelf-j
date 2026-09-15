package com.shelfj.einvoice;

/**
 * A document that cannot be read as an EN 16931 invoice at all, as distinct from an invoice that
 * reads but breaks a business rule (a {@link Violation}).
 *
 * <p>The {@link #code()} is stable so a service can map it to its own error without parsing the
 * message: {@code EMPTY}, {@code TOO_LARGE}, {@code NOT_XML}, {@code DTD_REFUSED}, {@code
 * NOT_AN_INVOICE}, {@code BAD_VALUE}, {@code NOT_A_PDF}, {@code PDF_UNREADABLE}, {@code
 * PDF_ENCRYPTED}, {@code NO_EMBEDDED_INVOICE}.
 */
public class EInvoiceFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public EInvoiceFormatException(String code, String message) {
    super(message);
    this.code = code;
  }

  public EInvoiceFormatException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** The stable reason the document was refused. */
  public String code() {
    return code;
  }
}
