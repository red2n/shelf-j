package com.shelfj.notification.service;

import com.shelfj.ids.Ids;
import com.shelfj.notification.domain.Domain.NotificationLog;
import com.shelfj.notification.domain.Domain.PushDevice;
import com.shelfj.notification.domain.Domain.ShortageAlert;
import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Business logic for notification-svc. Thin resource → this service → repository.
 *
 * <p>Every list method silently caps {@code limit} at 100 rather than rejecting a larger request,
 * matching the platform pagination ceiling.
 */
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

  /**
   * Lists recent stock-shortage alerts for a store, newest first.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose alerts to list
   * @param limit maximum rows to return; capped at 100
   * @return the matching alerts, newest first
   */
  public List<ShortageAlert> listAlerts(UUID tenantId, UUID storeId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlerts(tenantId, storeId, cap);
  }

  /**
   * Lists recent stock-shortage alerts for one variant across every store, newest first.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant whose alerts to list
   * @param limit maximum rows to return; capped at 100
   * @return the matching alerts, newest first
   */
  public List<ShortageAlert> listAlertsByVariant(UUID tenantId, UUID variantId, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listAlertsByVariant(tenantId, variantId, cap);
  }

  /** In-app notifications feed for a tenant, newest first (optionally filtered by recipient). */
  // ── push devices (13.7) ───────────────────────────────────────────────────

  /** How many devices one login may hold at one shop. */
  public static final int MAX_DEVICES = 10;

  /**
   * Registers the caller's device, or refreshes it when the token is already theirs.
   *
   * @throws ApiException {@code DEVICE_PLATFORM_UNKNOWN} (400); {@code PUSH_DEVICE_LIMIT} (409)
   */
  public PushDevice registerDevice(UUID tenantId, UUID userId, String platform, String token) {
    String p = platform.trim().toUpperCase(Locale.ROOT);
    if (!PushDevice.PLATFORMS.contains(p)) {
      throw ApiException.badRequest(
          "DEVICE_PLATFORM_UNKNOWN",
          "platform is one of " + String.join(", ", PushDevice.PLATFORMS) + ", not " + platform);
    }
    String t = token.trim();
    boolean known = repo.devicesFor(tenantId, userId).stream().anyMatch(d -> d.token().equals(t));
    if (!known && repo.countDevices(tenantId, userId) >= MAX_DEVICES) {
      throw ApiException.conflict(
          "PUSH_DEVICE_LIMIT", "a login holds at most " + MAX_DEVICES + " devices at one shop");
    }
    return repo.registerDevice(
        new PushDevice(Ids.newId(), tenantId, userId, p, t, Instant.now(), Instant.now()));
  }

  public List<PushDevice> listDevices(UUID tenantId, UUID userId) {
    return repo.devicesFor(tenantId, userId);
  }

  public boolean hasDevices(UUID tenantId, UUID userId) {
    return !repo.devicesFor(tenantId, userId).isEmpty();
  }

  /**
   * @throws ApiException {@code DEVICE_NOT_FOUND} (404) when the device is not the caller's
   */
  public void deleteDevice(UUID tenantId, UUID userId, UUID id) {
    if (!repo.deleteDevice(tenantId, userId, id)) {
      throw ApiException.notFound("DEVICE_NOT_FOUND", "no such device of yours");
    }
  }

  public List<NotificationLog> listNotifications(UUID tenantId, String recipient, int limit) {
    return listNotifications(tenantId, recipient, null, limit);
  }

  public List<NotificationLog> listNotifications(
      UUID tenantId, String recipient, String channel, int limit) {
    int cap = Math.min(limit, 100);
    return repo.listRecent(tenantId, recipient, channel, cap);
  }
}
