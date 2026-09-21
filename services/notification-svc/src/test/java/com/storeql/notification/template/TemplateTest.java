package com.storeql.notification.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The words of a message and their gaps: what a business can write, and what it cannot. */
class TemplateTest {

  private static final Locale EN_GB = Locale.forLanguageTag("en-GB");
  private static final Locale PL_GB =
      new Locale.Builder().setLanguage("pl").setRegion("GB").build();

  private static String render(String template, Values values, Locale locale) {
    return Template.parse(template).render(values.in(locale));
  }

  @Test
  @DisplayName("A value, a section when present, the other when not")
  void valuesAndSections() {
    Values v = Values.of().text("title", "Lock up").flag("required", true).flag("urgent", false);
    assertEquals(
        "Not done: Lock up, and it is required.",
        render("Not done: {{title}}{{#required}}, and it is required{{/required}}.", v, EN_GB));
    assertEquals(
        "not urgent",
        render("{{#urgent}}urgent{{/urgent}}{{^urgent}}not urgent{{/urgent}}", v, EN_GB));
    assertEquals(
        "[]", render("[{{#missing}}never{{/missing}}{{nothing}}]", v, EN_GB), "absent is empty");
  }

  @Test
  @DisplayName("A list repeats its section per item, knows its last, and sees the scope around it")
  void lists() {
    Values v =
        Values.of()
            .text("shop", "Hollins")
            .items(
                "products",
                List.of(
                    Values.of().text("name", "Peanut butter").text("lot", "L1"),
                    Values.of().text("name", "Oat bars")));
    assertEquals(
        "Peanut butter, lot L1 (Hollins); Oat bars (Hollins)",
        render(
            "{{#products}}{{name}}{{#lot}}, lot {{lot}}{{/lot}} ({{shop}}){{^last}}; {{/last}}"
                + "{{/products}}",
            v,
            EN_GB));
  }

  @Test
  @DisplayName("Money, days, moments and numbers are written the way the language writes them")
  void theLanguageWritesTheValues() {
    Values v =
        Values.of()
            .money("total", new BigDecimal("1234.5"), "GBP")
            .day("day", LocalDate.of(2026, 9, 21))
            .moment("at", Instant.parse("2026-09-21T08:05:00Z"))
            .number("reading", new BigDecimal("8.50"))
            .money("yen", new BigDecimal("4442"), "JPY");
    String t = "{{total}} | {{day}} | {{at}} | {{reading}} | {{yen}}";
    assertEquals(
        "£1,234.50 | 21 September 2026 | 21 September 2026, 08:05 UTC | 8.5 | JP¥4,442",
        render(t, v, EN_GB));
    // Polish groups with a no-break space and names a foreign currency by its code (CLDR).
    String pl = render(t, v, PL_GB).replace('\u00a0', ' ').replace('\u202f', ' ');
    assertEquals(
        "1 234,50 GBP | 21 września 2026 | 21 września 2026, 08:05 UTC | 8,5 | 4 442 JPY", pl);
  }

  @Test
  @DisplayName("A template that does not parse is refused, and says where")
  void whatDoesNotParse() {
    for (String bad :
        List.of(
            "{{title",
            "{{}}",
            "{{#open}}never closed",
            "closed {{/never}} opened",
            "{{#a}}{{#b}}{{/a}}{{/b}}",
            "{{ Title }}",
            "{{title.name}}",
            "{{>partial}}",
            "{{{html}}}",
            "{{&raw}}")) {
      assertThrows(Template.Invalid.class, () -> Template.parse(bad), bad);
    }
    Template.Invalid e = assertThrows(Template.Invalid.class, () -> Template.parse("ab {{x"));
    assertTrue(e.getMessage().contains("character 4"), e.getMessage());
  }

  @Test
  @DisplayName("It says which names it uses, sections included")
  void names() {
    assertEquals(
        Set.of("a", "b", "c"), Template.parse("{{a}}{{#b}}{{c}}{{/b}}{{^c}}x{{/c}}").names());
  }

  @Test
  @DisplayName("Braces a business types that are not a gap are only text")
  void plainText() {
    assertEquals(
        "Use {code} and a single } or {",
        render("Use {code} and a single } or {", Values.of(), EN_GB));
  }
}
