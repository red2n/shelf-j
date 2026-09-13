package com.shelfj.notification.provider;

/**
 * A provider could not deliver. {@code retryable} says whether trying again later could change the
 * answer (a timeout, a 5xx) or not (a bad number, a device the provider no longer knows).
 */
public class ProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The provider's answer for a device it no longer knows: the caller forgets the device. */
  public static final String UNREGISTERED = "UNREGISTERED";

  private final String code;
  private final boolean retryable;

  public ProviderException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public ProviderException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
