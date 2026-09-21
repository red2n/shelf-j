package com.storeql.notification.service;

import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Template;
import com.storeql.notification.template.TemplateStore;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes a message (13.x, message templates): the business's own words where it has written them,
 * the platform's where it has not, in the reader's language where the business has words in it.
 *
 * <p>The words are chosen in this order: the business's template in the reader's language, then in
 * the business's own default language, then the platform's, which is English. Whatever language the
 * words are in, the money, dates and numbers in them are written the way that language writes them,
 * in the business's country — so a Polish message from a British shop says {@code 12,50 GBP}, and
 * an English one {@code £12.50}.
 */
@ApplicationScoped
public class Messages {

  /** The language the platform's own words are in. */
  public static final String PLATFORM_LANGUAGE = "en";

  static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

  private static final System.Logger LOG = System.getLogger(Messages.class.getName());

  @Inject TemplateStore store;
  @Inject Businesses businesses;

  /**
   * A message to be written.
   *
   * @param language the reader's language, when it is known; null for the business's own
   */
  public record Message(String type, Catalogue.Form form, String language, Values values) {}

  /**
   * A message written.
   *
   * @param language the language it was written in
   * @param template {@code default} for the platform's words, {@code v3} for a business's third
   */
  public record Composed(String subject, String body, String language, String template) {}

  public Composed compose(UUID tenantId, Message m) {
    Catalogue.MessageType type = Catalogue.get(m.type());
    Catalogue.FormSpec spec =
        type.form(m.form())
            .orElseThrow(
                () -> new IllegalArgumentException(m.type() + " is not sent as " + m.form()));
    Optional<TemplateStore.Settings> settings =
        tenantId == null ? Optional.empty() : store.settings(tenantId);
    String house = settings.map(TemplateStore.Settings::defaultLanguage).orElse(PLATFORM_LANGUAGE);
    String wanted =
        m.language() != null && LANGUAGE.matcher(m.language()).matches() ? m.language() : house;

    Optional<TemplateStore.Stored> own = Optional.empty();
    if (tenantId != null) {
      own = store.live(tenantId, type.key(), m.form(), wanted);
      if (own.isEmpty() && !wanted.equals(house)) {
        own = store.live(tenantId, type.key(), m.form(), house);
      }
    }
    Values values = m.values();
    if (!values.has("shop")) values.text("shop", signOff(tenantId, settings));
    if (own.isPresent()) {
      TemplateStore.Stored t = own.get();
      try {
        return write(
            tenantId,
            m.form().hasSubject() ? t.subject() : spec.subject(),
            t.body(),
            t.language(),
            "v" + t.version(),
            values);
      } catch (Template.Invalid e) {
        // Checked when it was saved, so only a catalogue changed since could get here: the
        // platform's words go out rather than none.
        LOG.log(
            System.Logger.Level.WARNING,
            "template {0} v{1}: {2}",
            t.id(),
            t.version(),
            e.getMessage());
      }
    }
    return write(tenantId, spec.subject(), spec.body(), PLATFORM_LANGUAGE, "default", values);
  }

  private Composed write(
      UUID tenantId, String subject, String body, String language, String ref, Values values) {
    Locale locale = locale(language, tenantId);
    var scope = values.in(locale);
    return new Composed(
        Template.parse(subject).render(scope).strip(),
        Template.parse(body).render(scope).stripTrailing(),
        language,
        ref);
  }

  /** The language, in the business's country when it can be read. */
  Locale locale(String language, UUID tenantId) {
    String country = tenantId == null ? null : businesses.country(tenantId).orElse(null);
    return country == null
        ? Locale.forLanguageTag(language)
        : new Locale.Builder().setLanguage(language).setRegion(country).build();
  }

  private String signOff(UUID tenantId, Optional<TemplateStore.Settings> settings) {
    return settings
        .map(TemplateStore.Settings::signOff)
        .filter(s -> !s.isBlank())
        .or(() -> tenantId == null ? Optional.empty() : businesses.name(tenantId))
        .orElse("StoreQL");
  }
}
