package com.shelfj.notification.service;

import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Delivers one outbound notification, exactly once per {@code (eventId, type)}. A missing recipient
 * is skipped. On a channel failure the exception propagates (nothing is recorded) so the consumer
 * loop retries and re-sends; on success the send is recorded so a redelivered event is a no-op.
 */
@ApplicationScoped
public class Notifier {

  private static final Logger LOG = System.getLogger(Notifier.class.getName());

  @Inject NotificationChannel channel;
  @Inject NotificationRepository repo;

  public void notifyOnce(
      UUID eventId, String type, UUID tenantId, String recipient, String subject, String body) {
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No recipient for {0} {1} — skipped", type, eventId);
      return;
    }
    if (repo.alreadyNotified(eventId, type)) {
      return;
    }
    // Send first: a failure here throws and is NOT recorded, so the consumer redelivers and
    // retries.
    channel.send(tenantId, recipient, subject, body);
    repo.recordNotification(
        tenantId, eventId, type, channel.name(), recipient, subject, body, "SENT");
  }
}
