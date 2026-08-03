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

  /**
   * Instantiated by the {@code ServiceLoader} MicroProfile Config discovers on the classpath — do
   * not construct directly. Performs the one blocking HTTP fetch (or no-op) at construction time,
   * since {@link ConfigSource}s are built once and cached by the config provider.
   */
  public ConfigServiceConfigSource() {
    this.ordinal = parseInt(bootstrap("shelfj.config.ordinal"), DEFAULT_ORDINAL);
    this.properties = load();
  }

  /**
   * @return the fetched config key/value map, or {@link Map#of()} if {@code shelfj.config.url} is
   *     unset, {@code shelfj.service.name} is unknown, config-svc returns 404/non-200, or
   *     config-svc is unreachable — every failure mode degrades to "no central config" rather than
   *     throwing, per the class doc
   */
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
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
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

  /**
   * Extracts the {@code data} object of the config-svc response envelope into a flat string map.
   *
   * @param body the raw JSON response body from config-svc
   * @return the flattened {@code data} object's entries as strings; empty if {@code data} is
   *     missing or not a JSON object
   */
  private static Map<String, String> parseData(String body) {
    Map<String, String> out = new HashMap<>();
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      JsonValue data = root.get("data");
      if (data instanceof JsonObject obj) {
        for (var entry : obj.entrySet()) {
          JsonValue v = entry.getValue();
          out.put(entry.getKey(), v instanceof JsonString s ? s.getString() : v.toString());
        }
      }
    }
    return out;
  }

  /**
   * Resolve a bootstrap value before MP Config exists: system property → dotted env var (how
   * compose sets keys) → UPPER_SNAKE env var → the bundled {@code microprofile-config.properties}
   * (source of {@code shelfj.service.name}). Returns {@code null} if unset everywhere.
   *
   * @param key the dotted MicroProfile Config key, e.g. {@code "shelfj.config.url"}
   * @return the resolved value, or {@code null} if not set in any of the four sources checked
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

  /**
   * @return the bundled {@code META-INF/microprofile-config.properties}, loaded once and cached;
   *     empty (never {@code null}) if the resource is missing or unreadable
   */
  private Properties classpathDefaults() {
    if (classpathDefaults == null) {
      Properties p = new Properties();
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      if (cl != null) {
        try (var in = cl.getResourceAsStream("META-INF/microprofile-config.properties")) {
          if (in != null) p.load(in);
        } catch (java.io.IOException e) {
          LOG.log(Level.DEBUG, "Could not read bundled config defaults: {0}", e.getMessage());
        }
      }
      classpathDefaults = p;
    }
    return classpathDefaults;
  }

  /**
   * @param v the candidate value; may be {@code null}/blank
   * @param def the fallback
   * @return {@code v} if non-null and non-blank, otherwise {@code def}
   */
  private static String orDefault(String v, String def) {
    return v == null || v.isBlank() ? def : v;
  }

  /**
   * @param s a URL, possibly ending in {@code /}
   * @return {@code s} with any single trailing slash removed
   */
  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * @param s the string to parse; may be {@code null} or non-numeric
   * @param def the fallback
   * @return the parsed int, or {@code def} if {@code s} is {@code null} or not a valid integer
   */
  private static int parseInt(String s, int def) {
    try {
      return s == null ? def : Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  /**
   * @return an unmodifiable view of the fetched (or empty) config map
   */
  @Override
  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }

  /**
   * @return the keys this source can resolve; empty if central config wasn't fetched
   */
  @Override
  public Set<String> getPropertyNames() {
    return properties.keySet();
  }

  /**
   * @param key the MicroProfile Config key being resolved
   * @return the fetched value for {@code key}, or {@code null} if not present in central config
   *     (MicroProfile Config then falls through to the next-lower-ordinal source)
   */
  @Override
  public String getValue(String key) {
    return properties.get(key);
  }

  /**
   * @return {@value #NAME}, this source's identifier in MicroProfile Config diagnostics
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * @return this source's ordinal (default {@value #DEFAULT_ORDINAL}, overridable via {@code
   *     shelfj.config.ordinal}) — higher wins ties over the classpath properties file (100) but
   *     loses to env vars/system properties (300/400)
   */
  @Override
  public int getOrdinal() {
    return ordinal;
  }
}
