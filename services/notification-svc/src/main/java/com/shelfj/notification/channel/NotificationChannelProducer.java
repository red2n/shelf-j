package com.shelfj.notification.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Chooses the active {@link NotificationChannel} at startup from {@code
 * shelfj.notification.channel}: {@code app} (default — in-app notifications) or {@code email}
 * (SMTP). SMS/push are future channels added the same way. Keeping the selection here means the
 * consumers/service just inject {@link NotificationChannel} and never know which transport is live.
 */
@ApplicationScoped
public class NotificationChannelProducer {

  @Inject
  @ConfigProperty(name = "shelfj.notification.channel", defaultValue = "app")
  String channelName;

  @Inject
  @ConfigProperty(name = "shelfj.notification.from", defaultValue = "no-reply@shelf-j.local")
  String from;

  @Inject
  @ConfigProperty(name = "shelfj.notification.smtp.host", defaultValue = "localhost")
  String smtpHost;

  @Inject
  @ConfigProperty(name = "shelfj.notification.smtp.port", defaultValue = "587")
  int smtpPort;

  // Optional so an unset/blank credential is "no auth" rather than a failed injection.
  @Inject
  @ConfigProperty(name = "shelfj.notification.smtp.username")
  java.util.Optional<String> smtpUsername;

  @Inject
  @ConfigProperty(name = "shelfj.notification.smtp.password")
  java.util.Optional<String> smtpPassword;

  @Inject
  @ConfigProperty(name = "shelfj.notification.smtp.starttls", defaultValue = "true")
  boolean startTls;

  @Produces
  @ApplicationScoped
  public NotificationChannel channel() {
    if ("email".equalsIgnoreCase(channelName) || "smtp".equalsIgnoreCase(channelName)) {
      return new SmtpChannel(
          smtpHost, smtpPort, blankToNull(smtpUsername), blankToNull(smtpPassword), from, startTls);
    }
    return new AppChannel();
  }

  private static String blankToNull(java.util.Optional<String> v) {
    return v.filter(s -> !s.isBlank()).orElse(null);
  }
}
