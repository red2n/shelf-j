package com.shelfj.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes the application log level settable at deploy time instead of baked into the image.
 *
 * <p>{@code logging.properties} is read once by the JUL {@code LogManager} and has no
 * interpolation, so a level written there can only be changed by rebuilding. That is how every
 * service ended up shipping at {@code ALL}: every FINEST record from all 14 services formatted,
 * exported over OTLP, and stored — paid for at ingest, then filtered at query time. The files now
 * default to INFO and this raises or lowers {@code com.shelfj} at startup from the environment.
 *
 * <p>Reads {@code -Dshelfj.log.level} first, then {@code SHELFJ_LOG_LEVEL}, then leaves the file
 * default alone. Deliberately reads the environment directly rather than going through MicroProfile
 * Config: logging must be usable while the config subsystem is still starting, and a service that
 * cannot reach config-svc still needs to be able to say so.
 *
 * <p>Only {@code com.shelfj} moves. The per-package damping for Helidon, Weld, Kafka and friends
 * stays as written — turning application logging up should not also unleash framework internals.
 */
@ApplicationScoped
public class LogLevelConfigurer {

  static final String SYSTEM_PROPERTY = "shelfj.log.level";
  static final String ENV_VAR = "SHELFJ_LOG_LEVEL";
  static final String APPLICATION_LOGGER = "com.shelfj";

  private static final java.lang.System.Logger LOG =
      java.lang.System.getLogger(LogLevelConfigurer.class.getName());

  /**
   * Names people reach for that JUL does not know. Accepting them costs nothing and avoids a
   * silently ignored setting — the failure mode of a typo'd log level is that you get no logs
   * during the incident you set it for.
   */
  private static final Map<String, Level> ALIASES =
      Map.of(
          "TRACE", Level.FINEST,
          "DEBUG", Level.FINE,
          "WARN", Level.WARNING,
          "ERROR", Level.SEVERE,
          "FATAL", Level.SEVERE);

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    String configured = configuredValue();
    if (configured == null || configured.isBlank()) {
      return;
    }
    Level level = parse(configured);
    if (level == null) {
      LOG.log(
          java.lang.System.Logger.Level.WARNING,
          "Ignoring unrecognised {0}={1} — keeping the level from logging.properties",
          ENV_VAR,
          configured);
      return;
    }
    Logger.getLogger(APPLICATION_LOGGER).setLevel(level);
    LOG.log(
        java.lang.System.Logger.Level.INFO, "Application log level set to {0}", level.getName());
  }

  /** The configured level, system property winning over environment; null when neither is set. */
  static String configuredValue() {
    String fromProperty = java.lang.System.getProperty(SYSTEM_PROPERTY);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return fromProperty;
    }
    return java.lang.System.getenv(ENV_VAR);
  }

  /**
   * Parses a JUL level name or a common alias.
   *
   * @return the level, or null when {@code raw} is not recognised — the caller keeps the file
   *     default rather than guessing
   */
  static Level parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalised = raw.trim().toUpperCase(Locale.ROOT);
    Level alias = ALIASES.get(normalised);
    if (alias != null) {
      return alias;
    }
    try {
      return Level.parse(normalised);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
