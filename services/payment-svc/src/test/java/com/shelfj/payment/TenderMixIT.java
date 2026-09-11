package com.shelfj.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.payment.domain.Domain.TenderMixRow;
import com.shelfj.payment.repo.PaymentRepository;
import com.shelfj.payment.service.TenderMixService;
import com.shelfj.service.OutboxRow;
import com.shelfj.test.PostgresSupport;
import com.shelfj.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the tender-mix report against real Postgres.
 *
 * <p>The report is almost entirely one SQL statement, and the parts of it worth pinning are the
 * ones a unit test could not reach: that a method appearing on only one side of the UNION still
 * produces a row, that a failed tender counts without contributing money, and that the shares add
 * up to the whole.
 */
@HelidonTest
class TenderMixIT {

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

  @Inject TenderMixService service;
  @Inject PaymentRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /**
   * The shape of a real trading day: cash and card taken, one card sale refunded, one card attempt
   * declined. Each of those has to land in a different column.
   */
  @Test
  void splitsTheTakeByMethodAndSubtractsRefundsWithinTheirOwnMethod() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();

    UUID card = capture(tenant, order, "100.00", PaymentTender.METHOD_CARD, "CAPTURED");
    capture(tenant, order, "60.00", PaymentTender.METHOD_CASH, "CAPTURED");
    // A declined card attempt: it happened, but no money moved.
    capture(tenant, UUID.randomUUID(), "45.00", PaymentTender.METHOD_CARD, "FAILED");
    refund(tenant, order, card, "40.00", PaymentTender.METHOD_CARD);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null);
    assertThat(rows, hasSize(2));

    TenderMixRow cardRow = row(rows, PaymentTender.METHOD_CARD);
    assertThat(cardRow.capturedAmount().compareTo(new BigDecimal("100")), is(0));
    assertThat(cardRow.capturedCount(), is(1L));
    assertThat(cardRow.refundedAmount().compareTo(new BigDecimal("40")), is(0));
    assertThat(cardRow.refundedCount(), is(1L));
    // The decline is counted and contributes nothing to either money column.
    assertThat(cardRow.failedCount(), is(1L));
    assertThat(cardRow.netAmount().compareTo(new BigDecimal("60")), is(0));

    TenderMixRow cashRow = row(rows, PaymentTender.METHOD_CASH);
    assertThat(cashRow.netAmount().compareTo(new BigDecimal("60")), is(0));
    assertThat(cashRow.failedCount(), is(0L));

    // Net 60 each out of 120: the shares are halves and they add to the whole.
    assertThat(cardRow.shareOfNet().compareTo(new BigDecimal("50.0")), is(0));
    assertThat(cashRow.shareOfNet().compareTo(new BigDecimal("50.0")), is(0));

    // Ordered by net descending — the tie breaks on method name.
    assertThat(rows.get(0).method(), is(PaymentTender.METHOD_CARD));
  }

  /**
   * The case the UNION exists for: a refund issued through a method that took no money in the
   * window. A join between the two tables would have dropped this row, and it is the one an
   * accountant chases.
   */
  @Test
  void aMethodWithRefundsButNoCapturesStillAppears() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();

    UUID card = capture(tenant, order, "80.00", PaymentTender.METHOD_CARD, "CAPTURED");
    // Refunded onto a voucher rather than back to the card.
    refund(tenant, order, card, "25.00", PaymentTender.METHOD_VOUCHER);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null);
    TenderMixRow voucher = row(rows, PaymentTender.METHOD_VOUCHER);
    assertThat(voucher.capturedAmount().compareTo(BigDecimal.ZERO), is(0));
    assertThat(voucher.refundedAmount().compareTo(new BigDecimal("25")), is(0));
    assertThat(voucher.netAmount().compareTo(new BigDecimal("-25")), is(0));

    // A negative row still gets a share, of a total that is still positive: 80 - 25 = 55.
    assertThat(voucher.shareOfNet().compareTo(new BigDecimal("-45.5")), is(0));
  }

  /**
   * A window in which more went out than came in is a real day — the one after a recall. A share of
   * a non-positive total is meaningless, so it is null rather than a misleading percentage.
   */
  @Test
  void shareIsNullWhenThereIsNoPositiveTotalToShare() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();

    UUID card = capture(tenant, order, "30.00", PaymentTender.METHOD_CARD, "CAPTURED");
    refund(tenant, order, card, "30.00", PaymentTender.METHOD_CARD);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null);
    assertThat(row(rows, PaymentTender.METHOD_CARD).shareOfNet(), is(nullValue()));
  }

  /** The window bounds both sides of the UNION, and is validated before either is read. */
  @Test
  void isBoundedByItsWindowAndTenantScoped() {
    UUID tenant = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    capture(tenant, UUID.randomUUID(), "10.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(other, UUID.randomUUID(), "999.00", PaymentTender.METHOD_CASH, "CAPTURED");

    Instant now = Instant.now();
    // A window that closed before any of this happened sees none of it.
    assertThat(
        service.tenderMix(tenant, now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS)),
        hasSize(0));
    // One that contains it sees exactly its own tenant's 10.00.
    List<TenderMixRow> inWindow =
        service.tenderMix(tenant, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
    assertThat(inWindow, hasSize(1));
    assertThat(
        row(inWindow, PaymentTender.METHOD_CASH).netAmount().compareTo(BigDecimal.TEN), is(0));

    try {
      service.tenderMix(tenant, now, now.minus(1, ChronoUnit.HOURS));
      throw new AssertionError("a backwards window should be rejected");
    } catch (ApiException e) {
      assertThat(e.code(), is("PAYMENT_INVALID_PERIOD"));
    }
  }

  private static TenderMixRow row(List<TenderMixRow> rows, String method) {
    return rows.stream()
        .filter(r -> r.method().equals(method))
        .findFirst()
        .orElseThrow(() -> new AssertionError(method + " not in " + rows));
  }

  private UUID capture(UUID tenantId, UUID orderId, String amount, String method, String status) {
    UUID id = UUID.randomUUID();
    repo.createTender(
        new PaymentTender(
            id,
            tenantId,
            orderId,
            new BigDecimal(amount),
            method,
            null,
            null,
            status,
            null,
            Instant.now(),
            null),
        new OutboxRow("PaymentCaptured", "shelfj.payment.payment-captured", tenantId, id, "{}"));
    return id;
  }

  private void refund(UUID tenantId, UUID orderId, UUID paymentId, String amount, String method) {
    UUID id = UUID.randomUUID();
    repo.createRefundGuarded(
        new RefundTender(
            id,
            tenantId,
            orderId,
            paymentId,
            new BigDecimal(amount),
            method,
            null,
            null,
            "test",
            Instant.now()),
        new OutboxRow("PaymentRefunded", "shelfj.payment.payment-refunded", tenantId, id, "{}"));
  }
}
