package com.shelfj.service;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * MicroProfile {@link ConfigSource} that pulls non-secret configuration from the central config
 * service at startup and layers it <b>over</b> the local {@code microprofile-config.properties}
 * defaults (ordinal 150 &gt; the classpath file's 100) but <b>under</b> environment variables /
 * system properties (300 / 400). So deploy-time env still wins, config-svc overrides the baked
 * defaults, and the local file is the fallback — which is what makes golden rule #5 ("config is
 * external") real instead of aspirational.
 *
 * <p>Activated only when {@code shelfj.config.url} is set (env or system property); otherwise it is
 * an empty no-op, so local dev and tests run unchanged. If config-svc is unreachable, or has no
 * config for this service (404), it degrades to the empty source and the service still boots on its
 * local defaults — preserving "start in any order" (§17). Secrets never travel this path; they come
 * from env / a secret store. The one required credential, {@code shelfj.config.token}, is read from
 * the environment, not fetched.
 */
public final class ConfigServiceConfigSource implements ConfigSource {

  private static final Logger LOG = System.getLogger(ConfigServiceConfigSource.class.getName());
  private static final String NAME = "shelfj-config-service";
  private static final int DEFAULT_ORDINAL = 150;
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final Map<String, String> properties;
  private final int ordinal;

  private Properties classpathDefaults; // bootstrap fallback, loaded lazily

  public ConfigServiceConfigSource() {
    this.ordinal = parseInt(bootstrap("shelfj.config.ordinal"), DEFAULT_ORDINAL);
    this.properties = load();
  }

  private Map<String, String> load() {
    String url = bootstrap("shelfj.config.url");
    if (url == null || url.isBlank()) {
      return Map.of(); // not configured — no-op (local dev / tests)
    }
    String service = bootstrap("shelfj.service.name");
    if (service == null || service.isBlank()) {
      LOG.log(
          Level.WARNING,
          "shelfj.config.url is set but shelfj.service.name is unknown — skipping central config");
      return Map.of();
    }
    String profile = orDefault(bootstrap("shelfj.config.profile"), "default");
    String token = orDefault(bootstrap("shelfj.config.token"), "");
    String endpoint = stripTrailingSlash(url) + "/config/" + service + "/" + profile;
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(TIMEOUT)
              .header("X-Config-Token", token)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 404) {
        LOG.log(
            Level.INFO, "No central config for {0}/{1} — using local defaults", service, profile);
        return Map.of();
      }
      if (resp.statusCode() != 200) {
        LOG.log(
            Level.WARNING,
            "Central config fetch for {0} returned HTTP {1} — using local defaults",
            service,
            resp.statusCode());
        return Map.of();
      }
      Map<String, String> parsed = parseData(resp.body());
      LOG.log(
          Level.INFO,
          "Loaded {0} config key(s) from config-svc for {1}/{2}",
          parsed.size(),
          service,
          profile);
      return parsed;
    } catch (Exception e) {
      // Unreachable / timeout / interrupted — boot on local defaults rather than crash-looping.
      LOG.log(
          Level.WARNING,
          "config-svc unreachable ({0}) — using local defaults: {1}",
          endpoint,
          e.getMessage());
      return Map.of();
    }
  }

  /** Extracts the {@code data} object of the config-svc response envelope into a flat string map. */
  private static Map<String, String> parseData(String body) {
    Map<String, String> out = new HashMap<>();
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      JsonValue data = root.get("data");
      if (data instanceof JsonObject obj) {
        for (var entry : obj.entrySet()) {
          JsonValue v = entry.getValue();
          out.put(
              entry.getKey(),
              v instanceof JsonString s ? s.getString() : v.toString());
        }
      }
    }
    return out;
  }

  /**
   * Resolve a bootstrap value before MP Config exists: system property → dotted env var (how compose
   * sets keys) → UPPER_SNAKE env var → the bundled {@code microprofile-config.properties} (source of
   * {@code shelfj.service.name}). Returns {@code null} if unset everywhere.
   */
  private String bootstrap(String key) {
    String v = System.getProperty(key);
    if (v != null) return v;
    v = System.getenv(key);
    if (v != null) return v;
    v = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    if (v != null) return v;
    return classpathDefaults().getProperty(key);
  }

  private Properties classpathDefaults() {
    if (classpathDefaults == null) {
      Properties p = new Properties();
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      if (cl == null) cl = ConfigServiceConfigSource.class.getClassLoader();
      try (var in = cl.getResourceAsStream("META-INF/microprofile-config.properties")) {
        if (in != null) p.load(in);
      } catch (Exception e) {
        // best-effort bootstrap only — ignore
      }
      classpathDefaults = p;
    }
    return classpathDefaults;
  }

  private static String orDefault(String v, String def) {
    return v == null || v.isBlank() ? def : v;
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static int parseInt(String s, int def) {
    try {
      return s == null ? def : Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  @Override
  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public Set<String> getPropertyNames() {
    return properties.keySet();
  }

  @Override
  public String getValue(String key) {
    return properties.get(key);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getOrdinal() {
    return ordinal;
  }
}
