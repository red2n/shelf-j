package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.TemplateStore;
import com.storeql.notification.template.Values;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which words a message goes out in: the business's in the reader's language, then the business's
 * in its own language, then the platform's — and what the money looks like in each.
 */
class MessagesTest {

  private static final UUID TENANT = Ids.newId();

  private MessagesTestSupport.MemoryStore store;
  private Messages messages;

  @BeforeEach
  void setUp() {
    store = new MessagesTestSupport.MemoryStore();
    messages = MessagesTestSupport.with(store, "GB", "Hollins Grocers");
  }

  private Messages.Composed order(String language) {
    return messages.compose(
        TENANT,
        new Messages.Message(
            "ORDER_CONFIRMED",
            Catalogue.Form.EMAIL,
            language,
            Values.of().text("order", "A-1").money("total", new BigDecimal("12.5"), "GBP")));
  }

  @Test
  @DisplayName("Nobody has written anything: the platform's words, signed by the shop")
  void thePlatformsWords() {
    Messages.Composed m = order("pl");
    assertEquals("Your order is confirmed", m.subject());
    assertEquals(
        "Thanks for your order!\n\nOrder A-1\nTotal: £12.50\n\n— Hollins Grocers", m.body());
    assertEquals("en", m.language());
    assertEquals("default", m.template());
  }

  @Test
  @DisplayName("The reader's language when the business wrote it; its own language when not")
  void theReadersLanguageThenTheBusinesss() {
    store.put(
        "ORDER_CONFIRMED",
        Catalogue.Form.EMAIL,
        "pl",
        "Zamówienie {{order}} potwierdzone",
        "Dziękujemy! Razem: {{total}}. — {{shop}}");
    store.put(
        "ORDER_CONFIRMED",
        Catalogue.Form.EMAIL,
        "cy",
        "Archeb {{order}} wedi'i gadarnhau",
        "Diolch! Cyfanswm: {{total}}");
    store.settings = new TemplateStore.Settings("cy", "Siop Hollins");

    Messages.Composed polish = order("pl");
    assertEquals("Zamówienie A-1 potwierdzone", polish.subject());
    assertEquals(
        "Dziękujemy! Razem: 12,50 GBP. — Siop Hollins", polish.body().replace('\u00a0', ' '));
    assertEquals("pl", polish.language());
    assertEquals("v1", polish.template());

    Messages.Composed unknown = order("de");
    assertEquals("Archeb A-1 wedi'i gadarnhau", unknown.subject(), "German unwritten: the house's");
    assertEquals("cy", unknown.language());

    Messages.Composed nobodySaid = order(null);
    assertEquals("cy", nobodySaid.language());
  }

  @Test
  @DisplayName("A language that is not a language is the business's own")
  void rubbishIsTheBusinesssLanguage() {
    assertEquals("en", order("../../etc").language());
    assertEquals("en", order("POLISH").language());
  }

  @Test
  @DisplayName("A text message has no subject of its own: the log names it with the platform's")
  void aTextMessageKeepsItsLogSubject() {
    store.put(
        "RECALL_NOTICE",
        Catalogue.Form.SMS,
        "en",
        "ignored",
        "RECALL {{reference}}: {{product}}. {{remedies}}. {{contact}}");
    Messages.Composed sms =
        messages.compose(
            TENANT,
            new Messages.Message(
                "RECALL_NOTICE",
                Catalogue.Form.SMS,
                null,
                Values.of()
                    .text("reference", "R-9")
                    .text("product", "Oat bars")
                    .text("remedies", "a refund")
                    .text("contact", "0800 1")));
    assertEquals("Product safety recall — R-9", sms.subject());
    assertEquals("RECALL R-9: Oat bars. a refund. 0800 1", sms.body());
  }
}
