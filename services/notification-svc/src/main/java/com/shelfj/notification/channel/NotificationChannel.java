package com.shelfj.notification.channel;

/**
 * A pluggable outbound delivery channel. The active implementation is chosen at startup by {@link
 * NotificationChannelProducer} from {@code shelfj.notification.channel} — {@link LogChannel}
 * (default, dev/demo) or {@link SmtpChannel}. Adding SMS/push later is a new implementation, no
 * change to the consumers.
 */
public interface NotificationChannel {

  /** Channel id recorded in {@code notification_log} (e.g. {@code LOG}, {@code SMTP}). */
  String name();

  /** Deliver the message. Throws on failure so the caller can retry (nothing is recorded). */
  void send(String recipient, String subject, String body);
}
