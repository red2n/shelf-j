package com.storeql.iam.sso;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A provider's signing keys (RFC 7517), the RSA ones that sign, by key id. A key marked for
 * encryption, or for an algorithm other than RS256, is left out rather than trusted for something
 * it was never meant for.
 */
public final class Jwks {

  /** The id a key without one is filed under. */
  static final String NO_KID = "";

  private final Map<String, RSAPublicKey> keys;

  private Jwks(Map<String, RSAPublicKey> keys) {
    this.keys = Map.copyOf(keys);
  }

  public static Jwks parse(String json) {
    JsonObject doc;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      doc = reader.readObject();
    } catch (JsonException | IllegalStateException e) {
      throw new SsoRefused(SsoRefused.DISCOVERY_INVALID, "key set refused: not a JSON object", e);
    }
    if (!(doc.get("keys") instanceof jakarta.json.JsonArray list)) {
      throw invalid("no keys");
    }
    Map<String, RSAPublicKey> out = new LinkedHashMap<>();
    for (JsonValue v : list) {
      if (!(v instanceof JsonObject k)) continue;
      if (!"RSA".equals(k.getString("kty", ""))) continue;
      String use = k.getString("use", "sig");
      String alg = k.getString("alg", "RS256");
      if (!"sig".equals(use) || !"RS256".equals(alg)) continue;
      String n = k.getString("n", "");
      String e = k.getString("e", "");
      if (n.isEmpty() || e.isEmpty()) continue;
      // One malformed key does not make the others unusable.
      rsa(n, e).ifPresent(key -> out.put(k.getString("kid", NO_KID), key));
    }
    return new Jwks(out);
  }

  /**
   * The key a token names. A token that names none is matched only when the set holds exactly one
   * key, because otherwise which key signed it is a guess.
   */
  public Optional<RSAPublicKey> key(String kid) {
    if (kid != null && !kid.isEmpty()) return Optional.ofNullable(keys.get(kid));
    return keys.size() == 1 ? keys.values().stream().findFirst() : Optional.empty();
  }

  public int size() {
    return keys.size();
  }

  private static Optional<RSAPublicKey> rsa(String n, String e) {
    try {
      Base64.Decoder url = Base64.getUrlDecoder();
      var spec =
          new RSAPublicKeySpec(new BigInteger(1, url.decode(n)), new BigInteger(1, url.decode(e)));
      return Optional.of((RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec));
    } catch (GeneralSecurityException | IllegalArgumentException bad) {
      return Optional.empty();
    }
  }

  private static SsoRefused invalid(String why) {
    return new SsoRefused(SsoRefused.DISCOVERY_INVALID, "key set refused: " + why);
  }
}
