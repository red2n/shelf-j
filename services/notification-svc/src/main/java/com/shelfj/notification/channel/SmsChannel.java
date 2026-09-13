package com.shelfj.notification.channel;

import com.shelfj.notification.provider.Providers;
import com.shelfj.notification.provider.SmsProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Texts (13.7). The recipient is an E.164 number; the subject is not sent, a text has none, but it
 * is kept in the log like every other message. Bounded: ten segments is a letter, not a text.
 */
@ApplicationScoped
@Typed(SmsChannel.class)
public class SmsChannel implements NotificationChannel {

  public static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
  public static final int MAX_BODY = 1600;

  @Inject Providers providers;

  @Override
  public String name() {
    return "SMS";
  }

  /** The provider behind this channel, or null when the configured name is unknown. */
  public SmsProvider provider() {
    return providers.sms();
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    if (!E164.matcher(recipient).matches()) {
      throw new IllegalArgumentException("SMS recipient is not an E.164 number");
    }
    if (body.length() > MAX_BODY) {
      throw new IllegalArgumentException("SMS body longer than " + MAX_BODY + " characters");
    }
    SmsProvider p = provider();
    if (p == null || !p.isConfigured()) {
      throw new IllegalStateException("no SMS provider is configured");
    }
    p.send(recipient, body);
  }
}
