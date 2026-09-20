package com.storeql.gateway;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The deployment's {@code /.well-known/security.txt} (RFC 9116): where to report a vulnerability,
 * and until when that is true (21.15). Built from configuration only; a deployment that has not
 * configured a contact and a valid expiry publishes nothing rather than an invented address.
 */
public final class SecurityTxt {

  private static final Logger LOG = System.getLogger(SecurityTxt.class.getName());

  /** RFC 9116 recommends an expiry less than a year ahead, so the file is kept current. */
  static final Duration MAX_AHEAD = Duration.ofDays(366);

  private static final Pattern CONTACT =
      Pattern.compile(
          "^(mailto:[^\\s@<>\"]+@[^\\s@<>\"]+|https://[^\\s<>\"]+|tel:\\+?[0-9() -]{3,})$");
  private static final Pattern HTTPS = Pattern.compile("^https://[^\\s<>\"]+$");
  private static final Pattern LANGUAGES =
      Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*(, *[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*)*$");

  private SecurityTxt() {}

  /** What the gateway was configured with; every field may be absent. */
  public record Settings(
      Optional<String> contact,
      Optional<String> expires,
      Optional<String> policy,
      Optional<String> canonical,
      Optional<String> preferredLanguages) {}

  /**
   * @return the file, or empty when it must not be served; the reason is logged
   */
  static Optional<String> render(Settings settings, Instant now) {
    List<String> contacts = new ArrayList<>();
    for (String c : settings.contact().orElse("").split(",")) {
      String trimmed = c.trim();
      if (trimmed.isEmpty()) continue;
      if (!CONTACT.matcher(trimmed).matches()) {
        return refuse("a contact is not a mailto:, https: or tel: URI");
      }
      contacts.add(trimmed);
    }
    if (contacts.isEmpty()) return refuse("no contact is configured");
    Instant expires;
    try {
      expires = Instant.parse(settings.expires().map(String::trim).orElse(""));
    } catch (DateTimeParseException e) {
      return refuse("expires is not an ISO-8601 instant");
    }
    if (!expires.isAfter(now)) return refuse("it expired at " + expires);
    if (expires.isAfter(now.plus(MAX_AHEAD))) return refuse("expires is more than a year ahead");
    Optional<String> policy = settings.policy().map(String::trim);
    Optional<String> canonical = settings.canonical().map(String::trim);
    Optional<String> languages = settings.preferredLanguages().map(String::trim);
    if (policy.isPresent() && !HTTPS.matcher(policy.get()).matches()) {
      return refuse("the policy is not an https URL");
    }
    if (canonical.isPresent() && !HTTPS.matcher(canonical.get()).matches()) {
      return refuse("the canonical location is not an https URL");
    }
    if (languages.isPresent() && !LANGUAGES.matcher(languages.get()).matches()) {
      return refuse("preferred languages are not language tags");
    }
    StringBuilder out =
        new StringBuilder("# How to report a security vulnerability in this deployment.\n");
    contacts.forEach(c -> out.append("Contact: ").append(c).append('\n'));
    out.append("Expires: ").append(expires).append('\n');
    policy.ifPresent(p -> out.append("Policy: ").append(p).append('\n'));
    languages.ifPresent(l -> out.append("Preferred-Languages: ").append(l).append('\n'));
    canonical.ifPresent(c -> out.append("Canonical: ").append(c).append('\n'));
    return Optional.of(out.toString());
  }

  private static Optional<String> refuse(String why) {
    LOG.log(Level.WARNING, "security.txt not published: {0}", why);
    return Optional.empty();
  }
}
