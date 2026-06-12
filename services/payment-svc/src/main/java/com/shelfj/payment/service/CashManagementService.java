package com.shelfj.payment.service;

import com.shelfj.payment.domain.Domain.CashDrop;
import com.shelfj.payment.domain.Domain.TillSession;
import com.shelfj.payment.dto.Dtos.CashDropResponse;
import com.shelfj.payment.dto.Dtos.CloseTillRequest;
import com.shelfj.payment.dto.Dtos.OpenTillRequest;
import com.shelfj.payment.dto.Dtos.TenderSummary;
import com.shelfj.payment.dto.Dtos.TillReportResponse;
import com.shelfj.payment.dto.Dtos.TillSessionResponse;
import com.shelfj.payment.repo.CashManagementRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CashManagementService {

  @Inject CashManagementRepository repo;

  public TillSessionResponse openTill(UUID tenantId, UUID openedBy, OpenTillRequest req) {
    UUID storeId = UUID.fromString(req.storeId());
    TillSession session =
        new TillSession(
            UUID.randomUUID(),
            tenantId,
            storeId,
            openedBy,
            req.floatAmount(),
            TillSession.STATUS_OPEN,
            null,
            null,
            Instant.now(),
            null);
    return toSessionResponse(repo.openTill(session));
  }

  public TillSessionResponse getSession(UUID tenantId, UUID sessionId) {
    return toSessionResponse(requireSession(tenantId, sessionId));
  }

  public CashDropResponse recordDrop(
      UUID tenantId, UUID sessionId, UUID recordedBy, BigDecimal amount, String notes) {
    TillSession session = requireSession(tenantId, sessionId);
    if (!TillSession.STATUS_OPEN.equals(session.status())) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_DROP_AMOUNT", "Drop amount must be positive");
    }
    CashDrop drop =
        new CashDrop(
            UUID.randomUUID(), tenantId, sessionId, amount, recordedBy, notes, Instant.now());
    repo.recordDrop(drop);
    return new CashDropResponse(drop.id(), drop.tillSessionId(), drop.amount(), drop.createdAt());
  }

  /** X-report: read-only snapshot of the current session's totals. Does not close the session. */
  public TillReportResponse xReport(UUID tenantId, UUID sessionId) {
    TillSession session = requireSession(tenantId, sessionId);
    return buildReport(session, null);
  }

  /** Z-report: computes totals, records counted cash, closes the session. */
  public TillReportResponse zReport(UUID tenantId, UUID sessionId, CloseTillRequest req) {
    TillSession session = requireSession(tenantId, sessionId);
    if (!TillSession.STATUS_OPEN.equals(session.status())) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
    TillReportResponse report = buildReport(session, req.countedCash());
    BigDecimal overShort =
        req.countedCash()
            .subtract(
                report.expectedCashInTill() != null
                    ? report.expectedCashInTill()
                    : BigDecimal.ZERO);
    repo.closeTill(tenantId, sessionId, req.countedCash(), overShort);
    return report;
  }

  private TillReportResponse buildReport(TillSession session, BigDecimal countedCash) {
    Instant from = session.openedAt();
    Instant to = session.closedAt() != null ? session.closedAt() : Instant.now();

    List<Object[]> salesRows =
        repo.sumTendersByMethod(session.tenantId(), session.storeId(), from, to);
    List<Object[]> refundRows = repo.sumRefundsByMethod(session.tenantId(), from, to);

    Map<String, BigDecimal> sales = new HashMap<>();
    for (Object[] row : salesRows) {
      sales.put((String) row[0], (BigDecimal) row[1]);
    }
    Map<String, BigDecimal> refunds = new HashMap<>();
    for (Object[] row : refundRows) {
      refunds.put((String) row[0], (BigDecimal) row[1]);
    }

    Map<String, TenderSummary> summary = new HashMap<>();
    var allMethods = new java.util.HashSet<String>();
    allMethods.addAll(sales.keySet());
    allMethods.addAll(refunds.keySet());
    BigDecimal grossSales = BigDecimal.ZERO;
    BigDecimal totalRefunds = BigDecimal.ZERO;
    for (String method : allMethods) {
      BigDecimal s = sales.getOrDefault(method, BigDecimal.ZERO);
      BigDecimal r = refunds.getOrDefault(method, BigDecimal.ZERO);
      BigDecimal net = s.subtract(r);
      summary.put(method, new TenderSummary(s, r, net));
      grossSales = grossSales.add(s);
      totalRefunds = totalRefunds.add(r);
    }
    BigDecimal netSales = grossSales.subtract(totalRefunds);

    BigDecimal cashDropsTotal = repo.sumCashDrops(session.tenantId(), session.id());
    BigDecimal cashSales = sales.getOrDefault("CASH", BigDecimal.ZERO);
    BigDecimal cashRefunds = refunds.getOrDefault("CASH", BigDecimal.ZERO);
    BigDecimal expectedCash =
        session.floatAmount().add(cashSales).subtract(cashRefunds).subtract(cashDropsTotal);

    BigDecimal overShort = countedCash != null ? countedCash.subtract(expectedCash) : null;

    return new TillReportResponse(
        session.id(),
        session.storeId(),
        session.openedBy(),
        session.openedAt(),
        session.closedAt(),
        session.floatAmount(),
        Map.copyOf(summary),
        cashDropsTotal,
        expectedCash,
        countedCash,
        overShort,
        grossSales,
        totalRefunds,
        netSales);
  }

  private TillSession requireSession(UUID tenantId, UUID sessionId) {
    return repo.findSession(tenantId, sessionId)
        .orElseThrow(
            () -> ApiException.notFound("TILL_SESSION_NOT_FOUND", "Till session not found"));
  }

  private static TillSessionResponse toSessionResponse(TillSession s) {
    return new TillSessionResponse(
        s.id(),
        s.storeId(),
        s.openedBy(),
        s.floatAmount(),
        s.status(),
        s.countedCash(),
        s.overShort(),
        s.openedAt(),
        s.closedAt());
  }
}
