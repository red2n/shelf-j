package com.storeql.gateway.filters;

import com.storeql.discovery.ServiceRegistry;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The public keys access tokens are verified with (20.15), read from iam-svc's published key set.
 * The gateway holds nothing that can sign: a key is looked up by the id in a token's header, the
 * set is re-read when an id is unknown (a rotation) and at most every few seconds, and refreshed by
 * age so a retired key stops verifying here too. When iam-svc cannot be reached the last known set
 * keeps working, so an outage there does not sign everybody out.
 */
@ApplicationScoped
public class SigningKeySet {

  private static final System.Logger LOG = System.getLogger(SigningKeySet.class.getName());
  private static final String IAM_SERVICE = "iam-svc";
  private static final String JWKS_PATH = "/auth/.well-known/jwks.json";
  private static final Duration MIN_REFETCH = Duration.ofSeconds(5);

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;

  @Inject
  @ConfigProperty(name = "storeql.clients.iam-svc.url")
  Optional<String> iamUrl;

  @Inject
  @ConfigProperty(name = "storeql.gateway.jwks.refresh-seconds", defaultValue = "300")
  long refreshSeconds;

  private final Map<String, RSAPublicKey> keys = new ConcurrentHashMap<>();
  private volatile Instant fetchedAt = Instant.EPOCH;
  private volatile Instant attemptedAt = Instant.EPOCH;

  /** Whether any key set has ever been read: before that, no token can be judged at all. */
  public boolean loaded() {
    return !fetchedAt.equals(Instant.EPOCH);
  }

  /** The key with this id, re-reading the set when the id is new or the set has aged. */
  public Optional<RSAPublicKey> key(String kid) {
    if (kid == null || kid.isBlank()) return Optional.empty();
    Instant now = Instant.now();
    boolean stale = Duration.between(fetchedAt, now).getSeconds() > refreshSeconds;
    if ((stale || !keys.containsKey(kid))
        && Duration.between(attemptedAt, now).compareTo(MIN_REFETCH) > 0) {
      refresh(now);
    }
    return Optional.ofNullable(keys.get(kid));
  }

  private synchronized void refresh(Instant now) {
    if (Duration.between(attemptedAt, now).compareTo(MIN_REFETCH) <= 0) return;
    attemptedAt = now;
    Optional<String> base =
        iamUrl
            .filter(u -> !u.isBlank())
            .or(() -> registry.resolve(IAM_SERVICE).map(i -> i.baseUri()));
    if (base.isEmpty()) {
      LOG.log(System.Logger.Level.WARNING, "iam-svc is not in discovery: the key set was not read");
      return;
    }
    try (var res = webClient.get(base.get() + JWKS_PATH).request()) {
      if (res.status().code() != 200) {
        LOG.log(System.Logger.Level.WARNING, "key set read answered {0}", res.status().code());
        return;
      }
      Map<String, RSAPublicKey> fresh = parse(res.as(String.class));
      if (fresh.isEmpty()) return;
      keys.keySet().retainAll(fresh.keySet());
      keys.putAll(fresh);
      fetchedAt = now;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "key set could not be read: " + e.getMessage());
    }
  }

  /** The RSA signing keys of a JWKS document, by key id; anything else in it is left out. */
  static Map<String, RSAPublicKey> parse(String jwks) {
    Map<String, RSAPublicKey> out = new ConcurrentHashMap<>();
    try (var reader = Json.createReader(new StringReader(jwks))) {
      for (JsonObject k : reader.readObject().getJsonArray("keys").getValuesAs(JsonObject.class)) {
        if (!"RSA".equals(k.getString("kty", "")) || !k.containsKey("kid")) continue;
        if (!"RS256".equals(k.getString("alg", "RS256"))) continue;
        Base64.Decoder url = Base64.getUrlDecoder();
        RSAPublicKeySpec spec =
            new RSAPublicKeySpec(
                new BigInteger(1, url.decode(k.getString("n"))),
                new BigInteger(1, url.decode(k.getString("e"))));
        out.put(
            k.getString("kid"), (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec));
      }
    } catch (GeneralSecurityException | RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "key set could not be parsed: " + e.getMessage());
      return Map.of();
    }
    return out;
  }

  /** For tests: a key set already in hand. */
  static SigningKeySet of(Map<String, RSAPublicKey> known) {
    SigningKeySet set = new SigningKeySet();
    set.keys.putAll(known);
    set.fetchedAt = Instant.now();
    set.attemptedAt = Instant.now();
    set.refreshSeconds = 3600;
    return set;
  }
}
