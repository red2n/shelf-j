package com.shelfj.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.ids.Ids;
import com.shelfj.payment.domain.Terminals;
import com.shelfj.payment.service.TerminalService;
import com.shelfj.test.PostgresSupport;
import com.shelfj.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Taking a card on a terminal (07.16), against a real database.
 *
 * <p>The assertion this class exists for is the double charge: <b>the same idempotency key must
 * never reach the terminal twice.</b> That cannot be had from a unit test, because the guard is a
 * row and a primary key written in the same transaction as the attempt, before the device is asked
 * for anything.
 *
 * <p>The second is the timeout. A decline took nothing and a failure never started, but a timeout
 * means the card <em>may</em> have been charged — so it records no payment, and the attempt keeps
 * its provider reference so it can be found in the acquirer's settlement file. A platform that
 * treated it as a decline would lose a taking; one that treated it as an approval would claim money
 * it cannot prove.
 *
 * <p>The simulator picks its outcome from the amount's minor units, which is the acquirers' own
 * convention: {@code .01} declines, {@code .02} is cancelled, {@code .03} times out, {@code .04}
 * fails.
 */
@HelidonTest
class TerminalPaymentIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "payment");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  @Inject TerminalService svc;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID actor = Ids.newId();

  private Terminals.Terminal aTerminal(String label) {
    return svc.register(tenant, store, label + "-" + Ids.newId(), "SIMULATED", null, actor);
  }

  private Terminals.Attempt take(Terminals.Terminal t, String amount, String key) {
    return svc.sale(tenant, t.id(), Ids.newId(), new BigDecimal(amount), "GBP", actor, key);
  }

  // ── the double charge ──────────────────────────────────────────────────────

  @Test
  @DisplayName("The same key never reaches the terminal twice")
  void theSameKeyNeverReachesTheTerminalTwice() {
    Terminals.Terminal t = aTerminal("till");
    UUID order = Ids.newId();
    String key = "press-" + Ids.newId();

    var first = svc.sale(tenant, t.id(), order, new BigDecimal("12.50"), "GBP", actor, key);
    var second = svc.sale(tenant, t.id(), order, new BigDecimal("12.50"), "GBP", actor, key);

    assertThat("the retry is the first attempt, not a second one", second.id(), is(first.id()));
    assertThat(first.state(), is(Terminals.APPROVED));
    // One attempt on the record for one press, however many times it was pressed.
    assertThat(svc.attemptsOf(tenant, order), hasSize(1));
    assertThat(
        "and the same authorisation, not a second one on the customer's card",
        second.authCode(),
        is(first.authCode()));
  }

  @Test
  @DisplayName("Two different presses on one sale are two attempts, because they are two decisions")
  void twoDifferentPressesAreTwoAttempts() {
    Terminals.Terminal t = aTerminal("till");
    UUID order = Ids.newId();
    // Two cards for one sale — a split tender — and the guard must not collapse them. It keys on
    // the
    // press, not on the order, which is why a customer may pay half on each of two cards.
    svc.sale(tenant, t.id(), order, new BigDecimal("5.00"), "GBP", actor, "press-a-" + Ids.newId());
    svc.sale(tenant, t.id(), order, new BigDecimal("5.00"), "GBP", actor, "press-b-" + Ids.newId());
    assertThat(svc.attemptsOf(tenant, order), hasSize(2));
  }

  // ── what the terminal said ─────────────────────────────────────────────────

  @Test
  @DisplayName("An approval keeps what a card receipt has to carry")
  void anApprovalKeepsTheReceipt() {
    var approved = take(aTerminal("till"), "20.00", "k-" + Ids.newId());

    assertThat(approved.state(), is(Terminals.APPROVED));
    assertThat(approved.scheme(), is(not(nullValue())));
    assertThat(approved.panLast4(), is(not(nullValue())));
    assertThat(approved.authCode(), is(not(nullValue())));
    assertThat(approved.aid(), is(not(nullValue())));
    assertThat(approved.entryMode(), is(not(nullValue())));
    // The line a receipt prints, assembled once so every printer agrees.
    assertThat(approved.receiptLine(), is(not(nullValue())));
    assertThat(approved.receiptLine(), org.hamcrest.Matchers.containsString("****"));
    assertThat("and the full number is nowhere", approved.receiptLine().length() < 60, is(true));
  }

  @Test
  @DisplayName("A decline is settled, carries the terminal's reason, and took nothing")
  void aDecline() {
    var declined = take(aTerminal("till"), "9.01", "k-" + Ids.newId());

    assertThat(declined.state(), is(Terminals.DECLINED));
    assertThat(declined.settled(), is(true));
    assertThat(declined.outcomeDetail(), is(not(nullValue())));
    assertThat("nothing to print", declined.receiptLine(), is(nullValue()));
    assertThat("and no tender", declined.paymentId(), is(nullValue()));
  }

  @Test
  @DisplayName("A timeout keeps its reference, because that is how the money is traced")
  void aTimeout() {
    var timedOut = take(aTerminal("till"), "9.03", "k-" + Ids.newId());

    assertThat(timedOut.state(), is(Terminals.TIMED_OUT));
    assertThat(
        "no tender: the platform cannot prove the money moved",
        timedOut.paymentId(),
        is(nullValue()));
    // The reference is the only way to find this attempt in the acquirer's settlement file, which
    // is
    // the only way to learn what really happened. Dropping it would leave a possible charge with
    // nothing to match it to.
    assertThat(timedOut.providerRef(), is(not(nullValue())));
    assertThat(timedOut.settled(), is(true));
  }

  @Test
  @DisplayName("A cancellation and a failure are settled too — nothing is left REQUESTED")
  void cancelledAndFailed() {
    assertThat(
        take(aTerminal("till"), "9.02", "k-" + Ids.newId()).state(), is(Terminals.CANCELLED));
    assertThat(take(aTerminal("till"), "9.04", "k-" + Ids.newId()).state(), is(Terminals.FAILED));
    // An attempt stuck in REQUESTED is indistinguishable from one where the card may have been
    // charged, which is the state this row exists to avoid producing by accident.
  }

  // ── putting money back ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A refund goes back on the card that paid, and never more than it took")
  void aRefund() {
    Terminals.Terminal t = aTerminal("till");
    var sale = take(t, "30.00", "k-" + Ids.newId());

    var back = svc.refund(tenant, sale.id(), new BigDecimal("10.00"), actor, "r-" + Ids.newId());
    assertThat(back.state(), is(Terminals.APPROVED));
    assertThat(back.kind(), is(Terminals.REFUND));
    assertThat("it names what it puts back", back.refundOf(), is(sale.id()));

    ApiException tooMuch =
        assertThrows(
            ApiException.class,
            () -> svc.refund(tenant, sale.id(), new BigDecimal("40.00"), actor, "r2"));
    assertThat(tooMuch.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  @Test
  @DisplayName("Nothing is refunded against an attempt that took no money")
  void refundingADecline() {
    var declined = take(aTerminal("till"), "9.01", "k-" + Ids.newId());
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.refund(tenant, declined.id(), new BigDecimal("1.00"), actor, "r"));
    assertThat(e.code(), is("TERMINAL_NOT_APPROVED"));
  }

  @Test
  @DisplayName("A cancel that races an approval loses, and the approval stands")
  void cancelDoesNotUndoAnApproval() {
    // The state moves once out of REQUESTED and never back, so a cancel arriving after the
    // cardholder
    // has tapped cannot turn a taking into a cancellation.
    var approved = take(aTerminal("till"), "15.00", "k-" + Ids.newId());
    var after = svc.cancel(tenant, approved.id());
    assertThat(after.state(), is(Terminals.APPROVED));
  }

  // ── the register ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A retired terminal takes no more cards, and is not deleted")
  void aRetiredTerminal() {
    Terminals.Terminal t = aTerminal("till");
    var retired = svc.retire(tenant, t.id(), "screen cracked");
    assertThat(retired.status(), is(Terminals.RETIRED));
    assertThat(retired.retiredReason(), is("screen cracked"));

    ApiException e =
        assertThrows(ApiException.class, () -> take(retired, "5.00", "k-" + Ids.newId()));
    assertThat(e.code(), is("TERMINAL_RETIRED"));
    // Kept, because payments point at it.
    assertThat(svc.list(tenant).stream().anyMatch(x -> x.id().equals(t.id())), is(true));
  }

  @Test
  @DisplayName("Two active terminals cannot share a label in one store")
  void labelsAreUniquePerStore() {
    String label = "Till " + Ids.newId();
    svc.register(tenant, store, label, "SIMULATED", null, actor);
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.register(tenant, store, label, "SIMULATED", null, actor));
    assertThat(e.code(), is("TERMINAL_ALREADY_REGISTERED"));
  }

  @Test
  @DisplayName("A retired terminal's label is free for the device that replaces it")
  void aReplacementTakesTheLabel() {
    // Which is what happens when a pinpad is swapped after a fault, and a shop should not have to
    // invent "Till 2 (new)".
    String label = "Till " + Ids.newId();
    var first = svc.register(tenant, store, label, "SIMULATED", null, actor);
    svc.retire(tenant, first.id(), "swapped");
    var replacement = svc.register(tenant, store, label, "SIMULATED", null, actor);
    assertThat(replacement.id(), is(not(first.id())));
  }

  @Test
  @DisplayName("An unknown vendor is refused, and only configured ones are offered")
  void vendors() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.register(tenant, store, "Till X", "MY_OWN_PINPAD", null, actor));
    assertThat(e.code(), is("TERMINAL_VENDOR_UNKNOWN"));
    // The simulator is always available; a real vendor appears only when configured, so a business
    // is
    // not asked to pair a device the platform cannot reach.
    assertThat(svc.availableVendors(), contains("SIMULATED"));
  }

  @Test
  @DisplayName("One business never sees another's terminals or attempts")
  void oneBusinessNeverSeesAnothers() {
    Terminals.Terminal mine = aTerminal("till");
    UUID order = Ids.newId();
    svc.sale(tenant, mine.id(), order, new BigDecimal("7.00"), "GBP", actor, "k-" + Ids.newId());

    UUID rival = Ids.newId();
    assertThat(svc.list(rival), hasSize(0));
    assertThat(svc.attemptsOf(rival, order), hasSize(0));
    // And it cannot take a card on this business's device by naming its id.
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.sale(rival, mine.id(), Ids.newId(), new BigDecimal("7.00"), "GBP", actor, "k"));
    assertThat(e.code(), is("TERMINAL_NOT_FOUND"));
  }

  // ── what the amount may be ─────────────────────────────────────────────────

  @Test
  @DisplayName("An amount is positive money to two places, and a third is refused not rounded")
  void theAmount() {
    Terminals.Terminal t = aTerminal("till");
    for (String bad : new String[] {"0.00", "-5.00", "1.005"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.sale(tenant, t.id(), Ids.newId(), new BigDecimal(bad), "GBP", actor, "k"));
      assertThat(bad, e.code(), is("TERMINAL_AMOUNT_INVALID"));
    }
  }

  @Test
  @DisplayName("Nothing card-shaped is accepted, wherever it came from")
  void noCardNumbers() {
    // Belt to the gateway's braces: a PAN arriving by any other route is refused here, because
    // nothing downstream has anywhere to put one.
    ApiException e =
        assertThrows(
            ApiException.class, () -> TerminalService.refuseCardData("4242 4242 4242 4242"));
    assertThat(e.code(), is("TERMINAL_CARD_DATA_NOT_ACCEPTED"));
    assertThrows(ApiException.class, () -> TerminalService.refuseCardData("4242424242424242"));
    assertThrows(ApiException.class, () -> TerminalService.refuseCardData("4242-4242-4242-4242"));
    // And an ordinary label is not mistaken for one.
    TerminalService.refuseCardData("Till 2", "SN-90210", null, "");
  }
}
