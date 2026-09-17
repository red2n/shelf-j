package com.shelfj.payment.settlement;

/** A settlement file that cannot be read, said in words the person who uploaded it can act on. */
public final class SettlementFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public SettlementFileException(String code, String message) {
    super(message);
    this.code = code;
  }

  public SettlementFileException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** The stable machine code the API answers with. */
  public String code() {
    return code;
  }
}
