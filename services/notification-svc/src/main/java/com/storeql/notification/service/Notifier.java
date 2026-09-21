package com.storeql.notification.service;

import com.storeql.notification.channel.Channels;
import com.storeql.notification.channel.NotificationChannel;
import com.storeql.notification.repo.NotificationRepository;
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
  @Inject Channels channels;
  @Inject NotificationRepository repo;

  /**
   * @param subjectId the customer or account the message is about, so it can be found and erased
   *     later; null only when there is none
   */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      String subject,
      String body) {
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
        tenantId, subjectId, eventId, type, channel.name(), recipient, subject, body, "SENT");
  }

  /**
   * The same, on a named channel (13.7): EMAIL or APP for the configured default, SMS to a number,
   * PUSH to a login's devices. Idempotent on (eventId, type) like the default path.
   *
   * @throws IllegalArgumentException for a channel name that is not one of them
   */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      String subject,
      String body,
      String channelName) {
    NotificationChannel c = channels.forName(channelName);
    if (c == null) {
      throw new IllegalArgumentException("unknown channel " + channelName);
    }
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No recipient for {0} {1} — skipped", type, eventId);
      return;
    }
    if (repo.alreadyNotified(eventId, type)) {
      return;
    }
    c.send(tenantId, recipient, subject, body);
    repo.recordNotification(
        tenantId, subjectId, eventId, type, c.name(), recipient, subject, body, "SENT");
  }
}
