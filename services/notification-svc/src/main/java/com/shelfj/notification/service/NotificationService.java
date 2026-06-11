package com.shelfj.notification.service;

import com.shelfj.notification.domain.Domain.ShortageAlert;
import com.shelfj.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificationService {

  @Inject NotificationRepository repo;

  public ShortageAlert recordShortageAlert(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal available,
      BigDecimal threshold,
      UUID eventId) {
    var alert =
        new ShortageAlert(
            UUID.randomUUID(),
            tenantId,
            storeId,
            variantId,
            available,
            threshold,
            eventId,
            Instant.now());
    return repo.insertAlert(alert);
  }

  public List<ShortageAlert> listAlerts(UUID tenantId, UUID storeId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlerts(tenantId, storeId, cap);
  }

  public List<ShortageAlert> listAlertsByVariant(UUID tenantId, UUID variantId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlertsByVariant(tenantId, variantId, cap);
  }
}
