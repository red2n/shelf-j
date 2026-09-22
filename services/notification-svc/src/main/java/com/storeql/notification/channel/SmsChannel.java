package com.storeql.notification.channel;

import com.storeql.notification.provider.Providers;
import com.storeql.notification.provider.SmsProvider;
import com.storeql.notification.repo.SmsUsageRepository;
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

  /**
   * GSM 03.38's default alphabet: a text written only in these goes as 7-bit, 160 to a part.
   * Anything else — an emoji, a Polish ł, a Gujarati letter — sends the whole text as UCS-2, 70 to
   * a part.
   */
  private static final String GSM7 =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  /** The extension table: each of these costs two septets, an escape and itself. */
  private static final String GSM7_EXTENDED = "^{}\\[~]|€\f";

  @Inject Providers providers;

  /** Tells tenant-svc what was sent, to meter (21.10); absent where a test builds the channel. */
  @Inject SmsUsageRepository usage;

  private static final System.Logger LOG = System.getLogger(SmsChannel.class.getName());

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
    if (tenantId != null && usage != null) {
      try {
        usage.announce(tenantId, parts(body));
      } catch (RuntimeException e) {
        // Sent is sent. Throwing now would mark it failed and have it sent a second time; a text
        // that goes unmetered is the smaller wrong, and it is logged for whoever reconciles.
        LOG.log(
            System.Logger.Level.WARNING,
            "a text for {0} was sent but not metered: {1}",
            tenantId,
            e.getMessage());
      }
    }
  }

  /**
   * How many parts a carrier bills a text as (GSM 03.38 / 3GPP TS 23.038): 160 septets in one part
   * or 153 in each of several; a text with any character outside the GSM alphabet goes as UCS-2, 70
   * in one part or 67 in each of several.
   */
  public static int parts(String body) {
    String text = body == null ? "" : body;
    int septets = 0;
    boolean gsm = true;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (GSM7.indexOf(ch) >= 0) {
        septets++;
      } else if (GSM7_EXTENDED.indexOf(ch) >= 0) {
        septets += 2;
      } else {
        gsm = false;
        break;
      }
    }
    if (gsm) return septets <= 160 ? 1 : (septets + 152) / 153;
    int units = text.length();
    return units <= 70 ? 1 : (units + 66) / 67;
  }
}
