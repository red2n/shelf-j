package com.shelfj.notification.channel;

import com.shelfj.notification.domain.Domain.Channel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/**
 * The channels a message may be sent on (13.7), by name. EMAIL and APP are the deployment's
 * configured default channel — in-app alone, or SMTP or MQTT with in-app beside it — which is what
 * every message used before there was a choice; SMS and PUSH are the two this adds.
 */
@ApplicationScoped
public class Channels {

  @Inject NotificationChannel configured;
  @Inject SmsChannel sms;
  @Inject PushChannel push;

  /**
   * @param name EMAIL, SMS, PUSH or APP in any case; null or blank means EMAIL
   * @return the channel, or null when the name is not one of them
   */
  public NotificationChannel forName(String name) {
    String n =
        name == null || name.isBlank() ? Channel.EMAIL : name.trim().toUpperCase(Locale.ROOT);
    return switch (n) {
      case Channel.EMAIL, Channel.APP -> configured;
      case Channel.SMS -> sms;
      case Channel.PUSH -> push;
      default -> null;
    };
  }

  public NotificationChannel configured() {
    return configured;
  }

  public SmsChannel sms() {
    return sms;
  }

  public PushChannel push() {
    return push;
  }
}
