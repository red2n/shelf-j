package com.shelfj.notification.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Chooses the active {@link NotificationChannel} at startup from {@code
 * shelfj.notification.channel}:
 *
 * <ul>
 *   <li>{@code app} (default) — in-app feed only ({@link AppChannel})
 *   <li>{@code email} / {@code smtp} — SMTP <em>plus</em> in-app ({@link CompositeChannel}): the
 *       feed still records every send, and the message is emailed
 *   <li>{@code mqtt} — MQTT push <em>plus</em> in-app: for device-facing alerts (POS terminals,
 *       kiosk displays, platform console), not customer-facing notifications ({@link MqttChannel})
 * </ul>
 *
 * SMS is a future channel. Consumers inject {@link NotificationChannel} and never know which
 * transport is live.
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

  @Inject
  @ConfigProperty(name = "shelfj.notification.mqtt.host", defaultValue = "localhost")
  String mqttHost;

  @Inject
  @ConfigProperty(name = "shelfj.notification.mqtt.port", defaultValue = "1883")
  int mqttPort;

  @Inject
  @ConfigProperty(name = "shelfj.notification.mqtt.client-id", defaultValue = "notification-svc")
  String mqttClientId;

  @Inject
  @ConfigProperty(name = "shelfj.notification.mqtt.tls", defaultValue = "false")
  boolean mqttTls;

  @Inject
  @ConfigProperty(
      name = "shelfj.notification.mqtt.publisher-token-ttl-seconds",
      defaultValue = "2592000") // 30 days
  long mqttPublisherTokenTtlSeconds;

  // The MQTT broker authenticates every client, including this service's own publisher
  // connection, with a shelfj platform JWT (see infra/emqx.conf) — so notification-svc needs the
  // same signing secret/issuer as iam-svc.
  @Inject
  @ConfigProperty(name = "shelfj.jwt.secret")
  java.util.Optional<String> jwtSecret;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.issuer", defaultValue = "shelfj")
  String jwtIssuer;

  @Produces
  @ApplicationScoped
  public NotificationChannel channel() {
    AppChannel app = new AppChannel();
    if ("email".equalsIgnoreCase(channelName) || "smtp".equalsIgnoreCase(channelName)) {
      SmtpChannel smtp =
          new SmtpChannel(
              smtpHost,
              smtpPort,
              blankToNull(smtpUsername),
              blankToNull(smtpPassword),
              from,
              startTls);
      // Always keep the in-app path so the admin feed is populated when email is on.
      return new CompositeChannel(app, smtp);
    }
    if ("mqtt".equalsIgnoreCase(channelName)) {
      String token =
          MqttPublisherToken.mint(jwtSecret.orElse(null), jwtIssuer, mqttPublisherTokenTtlSeconds);
      MqttChannel mqtt =
          new MqttChannel(
              mqttHost,
              mqttPort,
              mqttClientId,
              MqttPublisherToken.PUBLISHER_IDENTITY,
              token,
              mqttTls);
      return new CompositeChannel(app, mqtt);
    }
    return app;
  }

  // Disposer, not a destructor call site: releases the MQTT connection on app shutdown /
  // redeploy so no netty threads leak. A no-op for the app/SMTP channels.
  public void disposeChannel(@Disposes NotificationChannel channel) {
    if (channel instanceof CompositeChannel composite
        && composite.external() instanceof MqttChannel mqtt) {
      mqtt.close();
    }
  }

  private static String blankToNull(java.util.Optional<String> v) {
    return v.filter(s -> !s.isBlank()).orElse(null);
  }
}
