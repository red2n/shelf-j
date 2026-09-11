package com.shelfj.notification.service;

import com.shelfj.ids.Ids;
import com.shelfj.notification.domain.Domain.NotificationLog;
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

  /** Record a shortage alert, deduped on eventId atomically with the insert. */
  public boolean recordShortageAlertOnce(
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal available,
      BigDecimal threshold,
      UUID eventId) {
    var alert =
        new ShortageAlert(
            Ids.newId(),
            tenantId,
            storeId,
            variantId,
            available,
            threshold,
            eventId,
            Instant.now());
    return repo.insertAlertOnce(consumerName, alert);
  }

  public List<ShortageAlert> listAlerts(UUID tenantId, UUID storeId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlerts(tenantId, storeId, cap);
  }

  public List<ShortageAlert> listAlertsByVariant(UUID tenantId, UUID variantId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlertsByVariant(tenantId, variantId, cap);
  }

  /** In-app notifications feed for a tenant, newest first (optionally filtered by recipient). */
  public List<NotificationLog> listNotifications(UUID tenantId, String recipient, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listRecent(tenantId, recipient, cap);
  }
}
