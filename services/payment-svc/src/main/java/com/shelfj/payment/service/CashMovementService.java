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

  /**
   * Records a pay-in or pay-out against an open till session.
   *
   * @param tenantId owning tenant
   * @param recordedBy the cashier recording the movement
   * @param req the till session, store, direction, amount, reason and optional authoriser
   * @param ctx caller context, checked for access to the store
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retry does not double the
   *     movement; blank is treated as absent
   * @return the recorded movement
   * @throws ApiException {@code INVALID_DIRECTION} (400) when direction is not {@code PAY_IN} or
   *     {@code PAY_OUT}
   */
  public CashMovementResponse recordMovement(
      UUID tenantId,
      UUID recordedBy,
      CashMovementRequest req,
      TenantContext ctx,
      String idempotencyKey) {
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
        recordedBy,
        idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
  }

  /**
   * Lists the pay-ins and pay-outs recorded against one till session.
   *
   * @param tenantId owning tenant
   * @param tillSessionId the session whose movements to list
   * @return the movements, empty when none were recorded
   */
  public List<CashMovementResponse> listMovements(UUID tenantId, UUID tillSessionId) {
    return repo.listMovements(tenantId, tillSessionId);
  }

  /**
   * Settles a store's business day, producing and storing its Z-report.
   *
   * @param tenantId owning tenant
   * @param generatedBy the user settling the day
   * @param req the store, business date, counted cash and optional currency (defaults to GBP)
   * @param ctx caller context, checked for access to the store
   * @return the settled Z-report
   * @throws ApiException {@code 400} when {@code businessDate} is not a valid date
   */
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

  /**
   * Reads a previously settled Z-report for a store and business date.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose report to read
   * @param businessDate the business date, as an ISO date string
   * @param ctx caller context, checked for access to the store
   * @return the stored Z-report
   * @throws ApiException {@code 400} when {@code businessDate} is not a valid date; {@code
   *     Z_REPORT_NOT_FOUND} (404) when that day has not been settled
   */
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
