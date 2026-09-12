package com.shelfj.pricing.provider;

import com.shelfj.pricing.domain.Domain.VatObligation;
import com.shelfj.pricing.domain.Domain.VatRegistration;
import com.shelfj.pricing.domain.Domain.VatReturn;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The path a VAT return takes to HMRC, behind one interface (18.5).
 *
 * <p>MTD's requirement is the digital link: the nine boxes go from the records that produced them
 * to HMRC with nobody typing them. {@code MtdService} computes the boxes and hands them here; the
 * provider files them. {@link HmrcMtdVatProvider} files with HMRC's VAT (MTD) API; {@link
 * SimulatedHmrcProvider} behaves as HMRC's sandbox does so the flow runs and is tested on a stack
 * with no HMRC application.
 */
public interface VatSubmissionProvider {

  /**
   * @return the {@code VatRegistration} provider constant this implementation serves
   */
  String name();

  /**
   * @return whether the deployment can use this provider — HMRC needs an application's credentials
   *     and a token key
   */
  boolean isConfigured();

  /**
   * The periods the taxpayer must file for between two dates.
   *
   * @param registration whose obligations
   * @param from inclusive
   * @param to inclusive
   * @param filedPeriodKeys the periods this service has already filed, for a provider that keeps no
   *     state of its own
   * @return the obligations, oldest first
   * @throws ProviderException when HMRC could not be reached or refused
   */
  List<VatObligation> obligations(
      VatRegistration registration, Instant from, Instant to, Set<String> filedPeriodKeys);

  /**
   * Files a return.
   *
   * @param registration whose return
   * @param periodKey the obligation it fulfils
   * @param boxes the nine boxes, as they will be sent
   * @param clientHeaders the fraud-prevention headers HMRC requires, as the caller's client
   *     collected them (empty for a simulated filing)
   * @return what HMRC answered
   * @throws ProviderException when HMRC refused the return, with HMRC's code
   */
  Receipt submit(
      VatRegistration registration,
      String periodKey,
      VatReturn boxes,
      Map<String, String> clientHeaders);

  /** HMRC's answer to an accepted return. */
  record Receipt(
      Instant processingDate,
      String formBundleNumber,
      String paymentIndicator,
      String chargeRefNumber,
      String receiptId,
      Instant receiptTimestamp) {}

  /** HMRC refused, or could not be reached. Carries HMRC's own code when there is one. */
  class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;

    /**
     * @param code HMRC's error code, e.g. {@code DUPLICATE_SUBMISSION}, or a name for the failure
     * @param message what went wrong
     * @param retryable whether trying again could plausibly succeed
     */
    public ProviderException(String code, String message, boolean retryable) {
      this(code, message, retryable, null);
    }

    /**
     * @param code HMRC's error code, or a name for the failure
     * @param message what went wrong
     * @param retryable whether trying again could plausibly succeed
     * @param cause the underlying failure, kept for the log
     */
    public ProviderException(String code, String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.code = code;
      this.retryable = retryable;
    }

    /**
     * @return HMRC's code, or the failure's name
     */
    public String code() {
      return code;
    }

    /**
     * @return whether retrying could plausibly succeed
     */
    public boolean retryable() {
      return retryable;
    }
  }
}
