package com.storeql.order.mapper;

import com.storeql.order.domain.EInvoiceTransports.Settings;
import com.storeql.order.domain.EInvoiceTransports.Transmission;
import com.storeql.order.dto.EInvoiceTransportDtos.ReadinessCheckResponse;
import com.storeql.order.dto.EInvoiceTransportDtos.ReadinessResponse;
import com.storeql.order.dto.EInvoiceTransportDtos.TransmissionResponse;
import com.storeql.order.dto.EInvoiceTransportDtos.TransportSettingsResponse;
import com.storeql.order.service.EInvoiceTransportService.Readiness;
import com.storeql.order.service.EInvoiceTransportService.SettingsView;

/** Transport settings and transmissions to their DTOs. */
public final class EInvoiceTransportMappers {

  private EInvoiceTransportMappers() {}

  public static TransportSettingsResponse toDto(SettingsView v) {
    Settings s = v.settings();
    return new TransportSettingsResponse(
        s.network(),
        s.provider(),
        s.providerAccount(),
        s.hasSecret(),
        str(s.updatedAt()),
        v.senderAddress(),
        v.suggested(),
        v.networks(),
        v.providers(),
        v.available(),
        v.needingSecret());
  }

  public static TransmissionResponse toDto(Transmission t) {
    return t == null
        ? null
        : new TransmissionResponse(
            t.id().toString(),
            t.invoiceId().toString(),
            t.network(),
            t.provider(),
            t.status(),
            t.attempts(),
            str(t.nextAttemptAt()),
            t.receiver(),
            t.providerRef(),
            t.detail(),
            str(t.createdAt()),
            str(t.sentAt()),
            str(t.settledAt()));
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  /** What stands between a business and its first e-invoice, on the wire. */
  public static ReadinessResponse toDto(Readiness r) {
    return new ReadinessResponse(
        r.network(),
        r.provider(),
        r.ready(),
        r.checks().stream()
            .map(c -> new ReadinessCheckResponse(c.code(), c.satisfied(), c.detail()))
            .toList(),
        r.probe() == null ? null : r.probe().state(),
        r.probe() == null ? null : r.probe().detail());
  }
}
