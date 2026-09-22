package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Meters.Alert;
import com.storeql.tenant.domain.Meters.Reading;
import com.storeql.tenant.domain.Meters.UsagePeriod;
import com.storeql.tenant.dto.UsageDtos;
import com.storeql.tenant.service.UsageService;

/** Usage (21.10) as the API shows it. */
public final class UsageMappers {

  private UsageMappers() {}

  public static UsageDtos.UsageResponse toDto(UsageService.Summary s) {
    return new UsageDtos.UsageResponse(
        s.period().start().toString(),
        s.period().end().toString(),
        s.period().trial(),
        s.currency(),
        s.planId() != null,
        s.readings().stream().map(UsageMappers::toDto).toList(),
        s.alerts().stream().map(UsageMappers::toDto).toList(),
        s.history().stream().map(UsageMappers::toDto).toList());
  }

  public static UsageDtos.AllowanceResponse toDto(UsageService.Allowance a) {
    return new UsageDtos.AllowanceResponse(
        a.meter(), a.used(), a.included(), a.hard(), a.quantity(), a.allowed());
  }

  public static UsageDtos.TenantAlertView acrossBusinesses(Alert a) {
    return new UsageDtos.TenantAlertView(
        a.tenantId().toString(),
        a.meter(),
        a.periodStart().toString(),
        a.threshold(),
        a.used(),
        a.included(),
        a.raisedAt().toString());
  }

  private static UsageDtos.MeterReading toDto(Reading r) {
    return new UsageDtos.MeterReading(
        r.meter().key(),
        r.meter().label(),
        r.meter().unit(),
        r.used(),
        r.included(),
        r.hard(),
        r.over(),
        r.currency(),
        r.unitAmount(),
        r.estimate());
  }

  private static UsageDtos.AlertView toDto(Alert a) {
    return new UsageDtos.AlertView(
        a.meter(), a.threshold(), a.used(), a.included(), a.raisedAt().toString());
  }

  private static UsageDtos.PeriodView toDto(UsagePeriod p) {
    return new UsageDtos.PeriodView(
        p.meter(),
        p.periodStart().toString(),
        p.periodEnd().toString(),
        p.used(),
        p.included(),
        p.overage(),
        p.currency(),
        p.unitAmount(),
        p.amount(),
        p.notCharged(),
        p.invoiceId() == null ? null : p.invoiceId().toString());
  }
}
