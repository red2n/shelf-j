package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogLevelConfigurerTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LogLevelConfigurer.SYSTEM_PROPERTY);
  }

  @Test
  void parsesJulLevelNames() {
    assertEquals(Level.INFO, LogLevelConfigurer.parse("INFO"));
    assertEquals(Level.FINE, LogLevelConfigurer.parse("FINE"));
    assertEquals(Level.FINEST, LogLevelConfigurer.parse("FINEST"));
    assertEquals(Level.SEVERE, LogLevelConfigurer.parse("SEVERE"));
    assertEquals(Level.OFF, LogLevelConfigurer.parse("OFF"));
    assertEquals(Level.ALL, LogLevelConfigurer.parse("ALL"));
  }

  @Test
  void parsesTheAliasesPeopleActuallyType() {
    // A typo'd level fails exactly when you need it, so the common non-JUL names are accepted.
    assertEquals(Level.FINE, LogLevelConfigurer.parse("DEBUG"));
    assertEquals(Level.FINEST, LogLevelConfigurer.parse("TRACE"));
    assertEquals(Level.WARNING, LogLevelConfigurer.parse("WARN"));
    assertEquals(Level.SEVERE, LogLevelConfigurer.parse("ERROR"));
    assertEquals(Level.SEVERE, LogLevelConfigurer.parse("FATAL"));
  }

  @Test
  void isCaseAndWhitespaceInsensitive() {
    assertEquals(Level.FINE, LogLevelConfigurer.parse("debug"));
    assertEquals(Level.INFO, LogLevelConfigurer.parse("  info  "));
    assertEquals(Level.WARNING, LogLevelConfigurer.parse("Warn"));
  }

  @Test
  void rejectsUnknownValuesSoTheFileDefaultSurvives() {
    assertNull(LogLevelConfigurer.parse("VERBOSE"));
    assertNull(LogLevelConfigurer.parse("banana"));
    assertNull(LogLevelConfigurer.parse(""));
    assertNull(LogLevelConfigurer.parse("   "));
    assertNull(LogLevelConfigurer.parse(null));
  }

  @Test
  void acceptsNumericLevelsBecauseJulDoes() {
    // Level.parse accepts the integer forms; no reason to be stricter than JUL itself.
    assertEquals(Level.INFO, LogLevelConfigurer.parse("800"));
  }

  @Test
  void systemPropertyIsRead() {
    System.setProperty(LogLevelConfigurer.SYSTEM_PROPERTY, "FINE");
    assertEquals("FINE", LogLevelConfigurer.configuredValue());
  }

  @Test
  void aBlankSystemPropertyFallsThroughRatherThanWinning() {
    System.setProperty(LogLevelConfigurer.SYSTEM_PROPERTY, "   ");
    // Falls through to the env var, which is unset in the test JVM.
    assertNull(LogLevelConfigurer.configuredValue());
  }

  @Test
  void nothingConfiguredMeansNoOverride() {
    assertNull(LogLevelConfigurer.configuredValue());
  }
}
