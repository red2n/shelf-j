package com.shelfj.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.payment.repo.PaymentRepository;
import com.shelfj.payment.service.PaymentService;
import com.shelfj.service.OutboxRow;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the automatic order-event refund path (N5). Exercises {@link
 * PaymentService#refundForOrderEvent} against real Postgres: return refunds are capped at the
 * captured total, cancellations refund the remaining, redelivery of the same order event is
 * deduped, and an unpaid order is a no-op.
 */
@HelidonTest
class PaymentRefundIT {

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

  private static final String CONSUMER = "payment-svc/order-refund";

  @Inject PaymentService service;
  @Inject PaymentRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private UUID captureTender(UUID tenantId, UUID orderId, String amount) {
    UUID tenderId = UUID.randomUUID();
    var tender =
        new PaymentTender(
            tenderId,
            tenantId,
            orderId,
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            null,
            null,
            "CAPTURED",
            null,
            Instant.now(),
            null);
    repo.createTender(
        tender,
        new OutboxRow(
            "PaymentCaptured", "shelfj.payment.payment-captured", tenantId, tenderId, "{}"));
    return tenderId;
  }

  private BigDecimal totalRefunded(UUID tenantId, UUID orderId) {
    return repo.findRefundsByOrder(tenantId, orderId).stream()
        .map(RefundTender::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** refund_tenders.amount is NUMERIC(14,4), so compare by value, not scale-sensitive equals. */
  private void assertRefunded(UUID tenantId, UUID orderId, String expected) {
    assertThat(totalRefunded(tenantId, orderId).compareTo(new BigDecimal(expected)), is(0));
  }

  @Test
  void returnRefundIsAppliedOnceAndCappedAtCaptured() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    captureTender(tenant, order, "40.00");

    UUID event = UUID.randomUUID();
    // Return refund of 15 against a 40 capture.
    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return");
    // Redelivery of the SAME order event must not refund again.
    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return");

    assertRefunded(tenant, order, "15.00");
  }

  @Test
  void returnRefundNeverExceedsCapturedTotal() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    captureTender(tenant, order, "30.00");

    // A return claiming more than was captured is capped at the captured 30.
    service.refundForOrderEvent(
        UUID.randomUUID(), CONSUMER, tenant, order, new BigDecimal("999.00"), "return");

    assertRefunded(tenant, order, "30.00");
  }

  @Test
  void cancellationRefundsAllRemainingAcrossSplitTenders() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    captureTender(tenant, order, "20.00");
    captureTender(tenant, order, "5.00");

    // null requested amount = refund whatever is still captured (25 across two tenders).
    service.refundForOrderEvent(UUID.randomUUID(), CONSUMER, tenant, order, null, "cancelled");

    assertRefunded(tenant, order, "25.00");
    // One refund row per tender touched.
    List<RefundTender> refunds = repo.findRefundsByOrder(tenant, order);
    assertThat(refunds.size(), is(2));
  }

  @Test
  void unpaidOrderCancellationIsANoOp() {
    UUID tenant = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    // No captured tender at all (e.g. pay-later order cancelled before payment).

    service.refundForOrderEvent(UUID.randomUUID(), CONSUMER, tenant, order, null, "cancelled");

    assertRefunded(tenant, order, "0");
  }
}
