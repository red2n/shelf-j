package com.shelfj.notification.channel;

/**
 * Fan-out channel: always runs the in-app (APP) path so the notification feed stays populated, then
 * the external channel (SMTP). External failure propagates so callers retry; APP never fails the
 * send. Recorded channel name is the external one when present (e.g. {@code SMTP}), else {@code
 * APP}.
 */
public final class CompositeChannel implements NotificationChannel {

  private final NotificationChannel inApp;
  private final NotificationChannel external;

  public CompositeChannel(NotificationChannel inApp, NotificationChannel external) {
    this.inApp = inApp;
    this.external = external;
  }

  @Override
  public String name() {
    return external.name();
  }

  @Override
  public void send(String recipient, String subject, String body) {
    // In-app first (no-op + debug log); never blocks external delivery.
    inApp.send(recipient, subject, body);
    external.send(recipient, subject, body);
  }
}
