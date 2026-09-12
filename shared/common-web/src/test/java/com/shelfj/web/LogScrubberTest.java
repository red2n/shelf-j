package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A card number written to a log is a card number stored; the scrubber is what stops that. */
class LogScrubberTest {

  /** Collects the records a handler would have written, after the scrubber has seen them. */
  private static final class Sink extends Handler {
    final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      if (isLoggable(record)) {
        records.add(record);
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static Sink loggerWithScrubber(String name) {
    Logger logger = Logger.getLogger(name);
    logger.setUseParentHandlers(false);
    Sink sink = new Sink();
    LogScrubber.install(sink);
    logger.addHandler(sink);
    return sink;
  }

  @Test
  @DisplayName("A card number in a message is masked before the handler sees it")
  void masksTheMessage() {
    Sink sink = loggerWithScrubber("scrub.message");
    Logger.getLogger("scrub.message").warning("payment failed for 4111 1111 1111 1111 at till 3");
    assertEquals(
        "payment failed for **** **** **** 1111 at till 3", sink.records.get(0).getMessage());
  }

  @Test
  @DisplayName("A card number in a parameter is masked too — that is where request bodies go")
  void masksParameters() {
    Sink sink = loggerWithScrubber("scrub.params");
    Logger.getLogger("scrub.params")
        .log(Level.INFO, "rejected body {0}", "{\"note\":\"card 378282246310005\"}");
    assertEquals("{\"note\":\"card ***********0005\"}", sink.records.get(0).getParameters()[0]);
    assertFalse(String.valueOf(sink.records.get(0).getParameters()[0]).contains("378282246310005"));
  }

  @Test
  @DisplayName("A record with no card number passes through untouched, barcode and all")
  void leavesOrdinaryRecordsAlone() {
    Sink sink = loggerWithScrubber("scrub.plain");
    Logger.getLogger("scrub.plain").info("received 12 of barcode 5012345678900");
    assertEquals("received 12 of barcode 5012345678900", sink.records.get(0).getMessage());
  }

  @Test
  @DisplayName("Installing on a handler that already filters keeps that filter")
  void keepsAnExistingFilter() {
    Sink sink = new Sink();
    sink.setFilter(r -> r.getLevel().intValue() >= Level.WARNING.intValue());
    LogScrubber.install(sink);
    Logger logger = Logger.getLogger("scrub.existing");
    logger.setUseParentHandlers(false);
    logger.addHandler(sink);
    logger.info("quiet 4111111111111111");
    logger.warning("loud 4111111111111111");
    assertEquals(1, sink.records.size());
    assertTrue(sink.records.get(0).getMessage().startsWith("loud ************1111"));
  }
}
