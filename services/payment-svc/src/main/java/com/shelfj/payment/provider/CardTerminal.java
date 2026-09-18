package com.shelfj.payment.provider;

import com.shelfj.payment.domain.Terminals;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A card terminal, behind one interface (07.16).
 *
 * <p>The same seam as {@link PaymentProvider} and for the same reason: one behaviour, several
 * vendors, and none of them available in a test. {@code TerminalService} never names Stripe
 * Terminal or Adyen, so a second vendor is a class and a configuration value rather than an edit to
 * the tender path.
 *
 * <p><b>No implementation ever sees a card number.</b> The platform sends an amount to a device and
 * receives a verdict; the EMV transaction happens inside the terminal, between the card and the
 * acquirer. That is what keeps this service out of PCI-DSS scope, and it is why {@link
 * Terminals.Outcome} carries four digits of the number and nothing more.
 *
 * <p><b>A timeout is not a failure.</b> An implementation that cannot get an answer must return
 * {@link Terminals#TIMED_OUT} and not {@link Terminals#FAILED}, because the card may have been
 * charged. The service treats the two differently, and getting it wrong here either loses a taking
 * or charges a customer twice.
 */
public interface CardTerminal {

  /** The {@code card_terminals.vendor} value this implementation serves. */
  String vendor();

  /**
   * Whether this vendor can be used — its credentials are configured and its device is reachable.
   *
   * <p>Asked before a terminal is registered against this vendor, so a business is told at that
   * point rather than at the till with a customer waiting.
   */
  boolean available();

  /**
   * Takes a sale on a terminal.
   *
   * <p>Blocks while the cardholder taps, inserts and enters a PIN, so implementations carry their
   * own timeout and return {@link Terminals#TIMED_OUT} when it expires rather than waiting for
   * ever.
   *
   * @param request what to take, and on which device
   * @return what the terminal said. Never null: an implementation that cannot decide returns {@link
   *     Terminals#FAILED} with a detail, because a null would be read as "nothing happened"
   */
  Terminals.Outcome sale(Request request);

  /**
   * Puts money back on the card that paid.
   *
   * <p>Linked to the original attempt rather than taking a card again: an unlinked refund is how
   * card fraud is done, and most acquirers refuse them outright.
   *
   * @param originalProviderRef the vendor's reference for the sale being refunded
   */
  Terminals.Outcome refund(Request request, String originalProviderRef);

  /**
   * Tells the device to stop asking for a card.
   *
   * <p>Used when the cashier abandons the tender. Best-effort by nature — the cardholder may have
   * completed it in the meantime, and an implementation that cannot cancel says so rather than
   * pretending, because the difference decides whether a tender is recorded.
   */
  Terminals.Outcome cancel(String providerRef);

  /**
   * What to ask of a terminal.
   *
   * @param terminalSerial the vendor's identifier for the device; how the vendor knows which
   *     counter
   * @param reference the platform's own reference for this attempt, passed to the vendor so its
   *     settlement file can be matched back without guessing
   */
  record Request(
      UUID terminalId,
      String terminalSerial,
      BigDecimal amount,
      String currency,
      UUID orderId,
      String reference) {}
}
