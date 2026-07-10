package com.shelfj.notification.mapper;

import com.shelfj.notification.domain.Domain.NotificationLog;
import com.shelfj.notification.domain.Domain.ShortageAlert;
import com.shelfj.notification.dto.Dtos.NotificationDto;
import com.shelfj.notification.dto.Dtos.ShortageAlertDto;

public final class Mappers {

  private Mappers() {}

  public static ShortageAlertDto toDto(ShortageAlert a) {
    return new ShortageAlertDto(
        a.id().toString(),
        a.tenantId().toString(),
        a.storeId().toString(),
        a.variantId().toString(),
        a.available(),
        a.threshold(),
        a.eventId().toString(),
        a.alertedAt());
  }

  public static NotificationDto toDto(NotificationLog n) {
    return new NotificationDto(
        n.id().toString(),
        n.type(),
        n.channel(),
        n.recipient(),
        n.subject(),
        n.body(),
        n.status(),
        n.createdAt());
  }
}
