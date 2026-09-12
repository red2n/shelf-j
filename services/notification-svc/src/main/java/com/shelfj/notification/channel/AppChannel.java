package com.shelfj.notification.channel;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Default channel: in-app notifications. The message is persisted to {@code notification_log} by
 * the {@link com.shelfj.notification.service.Notifier} and surfaced to the user in the app (see the
 * notifications feed endpoint) — no external push. Email ({@link SmtpChannel}) and, later, SMS are
 * additional channels selected via {@code shelfj.notification.channel}. {@code send} is a no-op
 * here because the persisted record IS the delivery; it logs at debug for observability.
 */
public final class AppChannel implements NotificationChannel {

  private static final Logger LOG = System.getLogger(AppChannel.class.getName());

  /**
   * {@inheritDoc}
   *
   * @return always {@code APP}
   */
  @Override
  public String name() {
    return "APP";
  }

  /**
   * {@inheritDoc}
   *
   * <p>A no-op beyond a debug log: the {@code notification_log} row written by the notifier is
   * itself the in-app delivery, so there is nothing to push.
   */
  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    // In-app delivery is the notification_log record itself (written by the Notifier); nothing is
    // pushed out of band. Logged so the message is visible in dev without a feed reader.
    LOG.log(Level.DEBUG, "[in-app → {0}] {1}", recipient, subject);
  }
}
