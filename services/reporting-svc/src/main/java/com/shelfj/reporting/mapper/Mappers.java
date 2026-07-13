package com.shelfj.reporting.mapper;

import com.shelfj.reporting.domain.Domain.InventoryProjection;
import com.shelfj.reporting.domain.Domain.MovementStat;
import com.shelfj.reporting.domain.Domain.OpenSupplyLine;
import com.shelfj.reporting.domain.Domain.SalesDayStat;
import com.shelfj.reporting.domain.Domain.SalesSummary;
import com.shelfj.reporting.dto.Dtos.MovementStatRow;
import com.shelfj.reporting.dto.Dtos.MovementStatsReport;
import com.shelfj.reporting.dto.Dtos.NettingReport;
import com.shelfj.reporting.dto.Dtos.NettingRow;
import com.shelfj.reporting.dto.Dtos.OnHandReport;
import com.shelfj.reporting.dto.Dtos.OnHandRow;
import com.shelfj.reporting.dto.Dtos.SalesByDayReport;
import com.shelfj.reporting.dto.Dtos.SalesDayRow;
import com.shelfj.reporting.dto.Dtos.SalesSummaryReport;
import com.shelfj.reporting.dto.Dtos.SalesSummaryRow;
import com.shelfj.reporting.service.ReportingService.NettingResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class Mappers {

  private Mappers() {}

  public static OnHandReport toOnHandReport(List<InventoryProjection> rows) {
    BigDecimal total =
        rows.stream().map(InventoryProjection::onHand).reduce(BigDecimal.ZERO, BigDecimal::add);
    var dtoRows =
        rows.stream()
            .map(
                p ->
                    new OnHandRow(
                        p.storeId().toString(),
                        p.variantId().toString(),
                        p.onHand(),
                        p.updatedAt()))
            .toList();
    return new OnHandReport(dtoRows, total);
  }

  public static NettingReport toNettingReport(NettingResult result) {
    // group supply lines by (storeId=toStoreId, variantId) to sum in-transit qty
    Map<String, BigDecimal> supplyTotals =
        result.supplyLines().stream()
            .collect(
                Collectors.toMap(
                    l -> key(l.toStoreId(), l.variantId()), OpenSupplyLine::qty, BigDecimal::add));

    var rows =
        result.onHand().stream()
            .map(
                p -> {
                  BigDecimal supply =
                      supplyTotals.getOrDefault(key(p.storeId(), p.variantId()), BigDecimal.ZERO);
                  return new NettingRow(
                      p.storeId().toString(),
                      p.variantId().toString(),
                      p.onHand(),
                      supply,
                      p.onHand().add(supply));
                })
            .toList();
    return new NettingReport(rows);
  }

  public static MovementStatsReport toMovementStatsReport(List<MovementStat> stats) {
    var rows =
        stats.stream()
            .map(
                s ->
                    new MovementStatRow(
                        s.storeId().toString(),
                        s.variantId().toString(),
                        s.bucket(),
                        s.totalIn(),
                        s.totalOut(),
                        s.totalIn().subtract(s.totalOut())))
            .toList();
    return new MovementStatsReport(rows);
  }

  public static SalesSummaryReport toSalesSummaryReport(List<SalesSummary> rows) {
    var dtoRows =
        rows.stream()
            .map(
                s ->
                    new SalesSummaryRow(s.currency(), s.orders(), s.gross(), s.refunded(), s.net()))
            .toList();
    return new SalesSummaryReport(dtoRows);
  }

  public static SalesByDayReport toSalesByDayReport(List<SalesDayStat> rows) {
    var dtoRows =
        rows.stream()
            .map(
                s ->
                    new SalesDayRow(
                        s.day(), s.currency(), s.orders(), s.gross(), s.refunded(), s.net()))
            .toList();
    return new SalesByDayReport(dtoRows);
  }

  private static String key(UUID storeId, UUID variantId) {
    return storeId + ":" + variantId;
  }
}
