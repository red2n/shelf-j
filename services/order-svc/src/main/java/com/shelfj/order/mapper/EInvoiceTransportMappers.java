package com.shelfj.order.mapper;

import com.shelfj.order.domain.EInvoiceTransports.Settings;
import com.shelfj.order.domain.EInvoiceTransports.Transmission;
import com.shelfj.order.dto.EInvoiceTransportDtos.TransmissionResponse;
import com.shelfj.order.dto.EInvoiceTransportDtos.TransportSettingsResponse;
import com.shelfj.order.service.EInvoiceTransportService.SettingsView;

/** Transport settings and transmissions to their DTOs. */
public final class EInvoiceTransportMappers {

  private EInvoiceTransportMappers() {}

  public static TransportSettingsResponse toDto(SettingsView v) {
    Settings s = v.settings();
    return new TransportSettingsResponse(
        s.network(),
        s.provider(),
        s.providerAccount(),
        str(s.updatedAt()),
        v.senderAddress(),
        v.suggested(),
        v.networks(),
        v.providers(),
        v.available());
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
}
