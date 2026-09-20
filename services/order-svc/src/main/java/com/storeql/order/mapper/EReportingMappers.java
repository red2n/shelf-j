package com.storeql.order.mapper;

import com.storeql.order.domain.EReporting.CrossBorderLine;
import com.storeql.order.domain.EReporting.Day;
import com.storeql.order.domain.EReporting.RateLine;
import com.storeql.order.domain.EReporting.Submission;
import com.storeql.order.dto.EReportingDtos;
import com.storeql.order.service.EReportingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** E-reporting on the wire (18.9). Money goes out as strings, scale intact. */
public final class EReportingMappers {

  private EReportingMappers() {}

  public static EReportingDtos.SubmissionResponse toDto(Submission s) {
    return new EReportingDtos.SubmissionResponse(
        s.id().toString(),
        s.returnCode(),
        text(s.periodStart()),
        text(s.periodEnd()),
        s.currency(),
        s.transactionCount(),
        text(s.netTotal()),
        text(s.vatTotal()),
        s.payloadDigest(),
        s.network(),
        s.provider(),
        s.status(),
        s.detail(),
        s.providerRef(),
        s.attempts(),
        text(s.createdAt()),
        text(s.transmittedAt()),
        text(s.supersedes()),
        text(s.supersededBy()));
  }

  public static List<EReportingDtos.SubmissionResponse> submissions(List<Submission> all) {
    return all.stream().map(EReportingMappers::toDto).toList();
  }

  public static EReportingDtos.PreviewResponse toDto(EReportingService.Preview p) {
    var c = p.content();
    return new EReportingDtos.PreviewResponse(
        c.returnCode(),
        text(c.periodStart()),
        text(c.periodEnd()),
        c.currency(),
        p.currencies(),
        p.transactionCount(),
        text(p.netTotal()),
        text(p.vatTotal()),
        c.empty(),
        c.days().stream().map(EReportingMappers::toDto).toList(),
        c.crossBorder().stream().map(EReportingMappers::toDto).toList(),
        p.standing() == null ? null : toDto(p.standing()));
  }

  private static EReportingDtos.DayResponse toDto(Day d) {
    return new EReportingDtos.DayResponse(
        text(d.day()),
        d.transactionCount(),
        text(d.net()),
        text(d.vat()),
        d.rates().stream().map(EReportingMappers::toDto).toList());
  }

  private static EReportingDtos.RateLineResponse toDto(RateLine r) {
    return new EReportingDtos.RateLineResponse(
        r.vatCode(), text(r.vatRate()), text(r.net()), text(r.vat()));
  }

  private static EReportingDtos.CrossBorderResponse toDto(CrossBorderLine c) {
    return new EReportingDtos.CrossBorderResponse(
        c.invoiceNumber(),
        text(c.issueDate()),
        c.buyerCountry(),
        c.buyerVatId(),
        c.currency(),
        text(c.net()),
        text(c.vat()));
  }

  private static String text(BigDecimal v) {
    return v == null ? null : v.toPlainString();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(LocalDate day) {
    return day == null ? null : day.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
