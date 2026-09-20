package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityTxtTest {

  private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

  private static SecurityTxt.Settings settings(
      String contact, String expires, String policy, String canonical, String languages) {
    return new SecurityTxt.Settings(
        Optional.ofNullable(contact),
        Optional.ofNullable(expires),
        Optional.ofNullable(policy),
        Optional.ofNullable(canonical),
        Optional.ofNullable(languages));
  }

  @Test
  @DisplayName(
      "A configured deployment publishes its contacts, expiry and policy, in RFC 9116 fields")
  void publishesWhatWasConfigured() {
    String body =
        SecurityTxt.render(
                settings(
                    "mailto:security@example.com, https://example.com/report",
                    "2027-06-30T00:00:00Z",
                    "https://example.com/security-policy",
                    "https://example.com/.well-known/security.txt",
                    "en, de"),
                NOW)
            .orElseThrow();
    assertTrue(body.contains("Contact: mailto:security@example.com\n"));
    assertTrue(body.contains("Contact: https://example.com/report\n"));
    assertTrue(body.contains("Expires: 2027-06-30T00:00:00Z\n"));
    assertTrue(body.contains("Policy: https://example.com/security-policy\n"));
    assertTrue(body.contains("Preferred-Languages: en, de\n"));
    assertTrue(body.contains("Canonical: https://example.com/.well-known/security.txt\n"));
    // Only the fields asked for: nothing optional appears when it was not configured.
    String minimal =
        SecurityTxt.render(
                settings("tel:+44 20 7946 0000", "2027-01-01T00:00:00Z", null, null, null), NOW)
            .orElseThrow();
    assertFalse(minimal.contains("Policy:"));
    assertEquals(3, minimal.lines().count());
  }

  @Test
  @DisplayName("No contact or no expiry publishes nothing rather than an invented file")
  void nothingIsInvented() {
    assertTrue(
        SecurityTxt.render(settings(null, "2027-01-01T00:00:00Z", null, null, null), NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(settings(" , ", "2027-01-01T00:00:00Z", null, null, null), NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(settings("mailto:s@example.com", null, null, null, null), NOW)
            .isEmpty());
  }

  @Test
  @DisplayName("An expired file, or one dated more than a year ahead, is not served")
  void theExpiryIsHonest() {
    assertTrue(
        SecurityTxt.render(
                settings("mailto:s@example.com", "2026-09-14T09:59:59Z", null, null, null), NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(settings("mailto:s@example.com", NOW.toString(), null, null, null), NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(
                settings(
                    "mailto:s@example.com",
                    NOW.plus(SecurityTxt.MAX_AHEAD).plus(Duration.ofSeconds(1)).toString(),
                    null,
                    null,
                    null),
                NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(settings("mailto:s@example.com", "30/06/2027", null, null, null), NOW)
            .isEmpty());
  }

  @Test
  @DisplayName(
      "A contact, policy or language that is not what it claims is refused, and no line is injected")
  void badValuesAreRefused() {
    for (String contact :
        new String[] {
          "security@example.com",
          "http://example.com/report",
          "mailto:a@b.com\nPolicy: https://evil.example",
          "javascript:alert(1)",
          "mailto:<script>@x.com"
        }) {
      assertTrue(
          SecurityTxt.render(settings(contact, "2027-01-01T00:00:00Z", null, null, null), NOW)
              .isEmpty(),
          contact);
    }
    assertTrue(
        SecurityTxt.render(
                settings(
                    "mailto:s@example.com",
                    "2027-01-01T00:00:00Z",
                    "http://example.com/p",
                    null,
                    null),
                NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(
                settings("mailto:s@example.com", "2027-01-01T00:00:00Z", null, "ftp://x", null),
                NOW)
            .isEmpty());
    assertTrue(
        SecurityTxt.render(
                settings(
                    "mailto:s@example.com",
                    "2027-01-01T00:00:00Z",
                    null,
                    null,
                    "english\nContact: x"),
                NOW)
            .isEmpty());
  }
}
