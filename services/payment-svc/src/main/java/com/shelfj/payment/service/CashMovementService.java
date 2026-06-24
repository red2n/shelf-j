package com.shelfj.payment.service;

import com.shelfj.payment.dto.Dtos.CashMovementRequest;
import com.shelfj.payment.dto.Dtos.CashMovementResponse;
import com.shelfj.payment.dto.Dtos.GenerateZReportRequest;
import com.shelfj.payment.dto.Dtos.ZReportResponse;
import com.shelfj.payment.repo.CashMovementRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Pay-in / pay-out (petty cash) and daily Z-report settlement. */
@ApplicationScoped
public class CashMovementService {

  @Inject CashMovementRepository repo;

  public CashMovementResponse recordMovement(
      UUID tenantId, UUID recordedBy, CashMovementRequest req, TenantContext ctx) {
    if (!"PAY_IN".equals(req.direction()) && !"PAY_OUT".equals(req.direction())) {
      throw new ApiException(
          400, "INVALID_DIRECTION", "direction must be PAY_IN or PAY_OUT", List.of());
    }
    UUID tillSessionId = UUID.fromString(req.tillSessionId());
    UUID storeId = UUID.fromString(req.storeId());
    ctx.requireStoreAccess(storeId);
    UUID authorisedBy = req.authorisedBy() == null ? null : UUID.fromString(req.authorisedBy());
    return repo.insertMovement(
        tenantId,
        storeId,
        tillSessionId,
        req.direction(),
        req.amount(),
        req.reason(),
        authorisedBy,
        recordedBy);
  }

  public List<CashMovementResponse> listMovements(UUID tenantId, UUID tillSessionId) {
    return repo.listMovements(tenantId, tillSessionId);
  }

  public ZReportResponse generateZReport(
      UUID tenantId, UUID generatedBy, GenerateZReportRequest req, TenantContext ctx) {
    UUID storeId = UUID.fromString(req.storeId());
    ctx.requireStoreAccess(storeId);
    LocalDate businessDate = com.shelfj.web.Parsing.date(req.businessDate(), "businessDate");
    String currency =
        req.currency() == null || req.currency().isBlank()
            ? "GBP"
            : req.currency().toUpperCase(Locale.ROOT);
    return repo.generateZReport(
        tenantId, storeId, businessDate, req.countedCash(), currency, generatedBy);
  }

  public ZReportResponse getZReport(
      UUID tenantId, UUID storeId, String businessDate, TenantContext ctx) {
    ctx.requireStoreAccess(storeId);
    LocalDate date = com.shelfj.web.Parsing.date(businessDate, "businessDate");
    return repo.findZReport(tenantId, storeId, date)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "Z_REPORT_NOT_FOUND", "No Z-report found for that store and date"));
  }
}
