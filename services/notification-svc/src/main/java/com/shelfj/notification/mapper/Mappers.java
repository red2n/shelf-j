package com.shelfj.notification.mapper;

import com.shelfj.notification.domain.Domain.NotificationLog;
import com.shelfj.notification.domain.Domain.ShortageAlert;
import com.shelfj.notification.dto.Dtos.NotificationDto;
import com.shelfj.notification.dto.Dtos.ShortageAlertDto;

/** Maps notification-svc domain records to the DTOs served over HTTP. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a stock-shortage alert to its wire form.
   *
   * @param a the alert to convert
   * @return the DTO, with every id rendered as a string
   */
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

  /**
   * Converts a notification-log entry to its wire form for the in-app feed.
   *
   * @param n the logged notification to convert
   * @return the DTO, with every id rendered as a string
   */
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
