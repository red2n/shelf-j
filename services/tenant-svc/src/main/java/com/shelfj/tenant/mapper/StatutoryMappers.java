package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.StatutoryReturns.Filing;
import com.shelfj.tenant.domain.StatutoryReturns.Obligation;
import com.shelfj.tenant.dto.StatutoryDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Statutory obligations and filings as the wire carries them. */
public final class StatutoryMappers {

  private StatutoryMappers() {}

  public static StatutoryDtos.ObligationResponse toDto(Obligation o) {
    return new StatutoryDtos.ObligationResponse(
        o.owed().code(),
        o.owed().name(),
        o.owed().scopeKind(),
        o.owed().scope(),
        o.owed().frequency(),
        text(o.periodStart()),
        text(o.periodEnd()),
        text(o.dueOn()),
        o.state(),
        o.owed().citation(),
        o.owed().exportService(),
        o.owed().exportPath(),
        o.filing() == null ? null : toDto(o.filing()));
  }

  public static StatutoryDtos.StatutoryFilingResponse toDto(Filing f) {
    return new StatutoryDtos.StatutoryFilingResponse(
        f.id().toString(),
        f.returnCode(),
        text(f.periodStart()),
        text(f.periodEnd()),
        text(f.filedAt()),
        f.reference(),
        f.provider(),
        f.payloadDigest(),
        f.supersedes() == null ? null : f.supersedes().toString(),
        f.stands(),
        f.note());
  }

  public static List<StatutoryDtos.ObligationResponse> obligations(List<Obligation> obligations) {
    return obligations.stream().map(StatutoryMappers::toDto).toList();
  }

  public static List<StatutoryDtos.StatutoryFilingResponse> filings(List<Filing> filings) {
    return filings.stream().map(StatutoryMappers::toDto).toList();
  }

  /** The whole calendar and, separately, the part of it somebody has to act on. */
  public static StatutoryDtos.CalendarResponse calendar(
      LocalDate asOf, List<Obligation> all, List<Obligation> outstanding) {
    return new StatutoryDtos.CalendarResponse(
        asOf.toString(), obligations(all), obligations(outstanding));
  }

  private static String text(LocalDate day) {
    return day == null ? null : day.toString();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }
}
