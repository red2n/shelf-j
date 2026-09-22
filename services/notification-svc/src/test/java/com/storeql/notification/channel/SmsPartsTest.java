package com.storeql.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many parts a carrier bills a text as (GSM 03.38 / 3GPP TS 23.038), which is what the platform
 * meters (21.10): the boundaries are where an invoice would be argued about.
 */
class SmsPartsTest {

  @Test
  @DisplayName("GSM-7: 160 septets in one part, then 153 in each")
  void gsm() {
    assertEquals(1, SmsChannel.parts(""));
    assertEquals(1, SmsChannel.parts("a".repeat(160)));
    assertEquals(2, SmsChannel.parts("a".repeat(161)));
    assertEquals(2, SmsChannel.parts("a".repeat(306)));
    assertEquals(3, SmsChannel.parts("a".repeat(307)));
    // é, à, Ø and £ are in the GSM alphabet: accents alone do not make a text UCS-2.
    assertEquals(2, SmsChannel.parts("Café à Øresund, £5. ".repeat(9)));
  }

  @Test
  @DisplayName("The extension table costs two septets a character")
  void extension() {
    assertEquals(1, SmsChannel.parts("{".repeat(80)));
    assertEquals(2, SmsChannel.parts("{".repeat(81)));
    assertEquals(1, SmsChannel.parts("€".repeat(80)));
  }

  @Test
  @DisplayName("One character outside the alphabet sends the whole text as UCS-2: 70, then 67")
  void ucs2() {
    assertEquals(1, SmsChannel.parts("ł" + "a".repeat(69)));
    assertEquals(2, SmsChannel.parts("ł" + "a".repeat(70)));
    assertEquals(2, SmsChannel.parts("ł" + "a".repeat(133)));
    assertEquals(3, SmsChannel.parts("ł" + "a".repeat(134)));
    // An emoji is two UTF-16 units, and a carrier counts both.
    assertEquals(2, SmsChannel.parts("😀".repeat(36)));
  }
}
