package com.storeql.iam.sso;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What a provider's discovery document says (OpenID Connect Discovery 1.0 §3), held to what this
 * client needs: the authorization code flow, RS256 ID tokens, S256 PKCE, and a client secret sent
 * one of the two ways it knows.
 *
 * @param tokenAuthMethod {@code client_secret_basic} or {@code client_secret_post}, the first the
 *     provider accepts
 */
public record Discovery(
    String issuer,
    String authorizationEndpoint,
    String tokenEndpoint,
    String jwksUri,
    Optional<String> userinfoEndpoint,
    String tokenAuthMethod) {

  public static final String BASIC = "client_secret_basic";
  public static final String POST = "client_secret_post";

  /** Where the document lives for an issuer: its path under the issuer, never a slash doubled. */
  public static String location(String issuer) {
    String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    return base + "/.well-known/openid-configuration";
  }

  /**
   * Reads a discovery document.
   *
   * @param expectedIssuer the issuer the document was fetched for; §4.3 requires the document to
   *     name exactly this one, or a document served at one address could speak for another
   * @throws SsoRefused {@link SsoRefused#DISCOVERY_INVALID} when it will not do
   */
  public static Discovery parse(String json, String expectedIssuer) {
    JsonObject doc;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      doc = reader.readObject();
    } catch (JsonException | IllegalStateException e) {
      throw new SsoRefused(
          SsoRefused.DISCOVERY_INVALID, "discovery document refused: not a JSON object", e);
    }
    String issuer = string(doc, "issuer").orElseThrow(() -> invalid("no issuer"));
    if (!issuer.equals(expectedIssuer)) {
      throw invalid("it names the issuer " + issuer + ", not " + expectedIssuer);
    }
    String authorization =
        string(doc, "authorization_endpoint")
            .orElseThrow(() -> invalid("no authorization_endpoint"));
    String token = string(doc, "token_endpoint").orElseThrow(() -> invalid("no token_endpoint"));
    String jwks = string(doc, "jwks_uri").orElseThrow(() -> invalid("no jwks_uri"));
    // Each list is judged only when present: a provider that leaves one out is not refused for
    // it, but one that says it cannot do what this client does is.
    strings(doc, "response_types_supported")
        .filter(s -> !s.contains("code"))
        .ifPresent(s -> refuse("it does not offer the authorization code flow"));
    strings(doc, "id_token_signing_alg_values_supported")
        .filter(s -> !s.contains("RS256"))
        .ifPresent(s -> refuse("it does not sign ID tokens with RS256"));
    strings(doc, "code_challenge_methods_supported")
        .filter(s -> !s.contains("S256"))
        .ifPresent(s -> refuse("it does not accept S256 PKCE"));
    // RFC 8414 §2: absent, the provider takes client_secret_basic.
    Set<String> auth = strings(doc, "token_endpoint_auth_methods_supported").orElse(Set.of(BASIC));
    String method;
    if (auth.contains(BASIC)) {
      method = BASIC;
    } else if (auth.contains(POST)) {
      method = POST;
    } else {
      throw invalid("it takes neither client_secret_basic nor client_secret_post");
    }
    return new Discovery(
        issuer, authorization, token, jwks, string(doc, "userinfo_endpoint"), method);
  }

  private static Optional<String> string(JsonObject doc, String name) {
    JsonValue v = doc.get(name);
    if (v instanceof JsonString s && !s.getString().isBlank()) return Optional.of(s.getString());
    return Optional.empty();
  }

  private static Optional<Set<String>> strings(JsonObject doc, String name) {
    JsonValue v = doc.get(name);
    if (!(v instanceof JsonArray a)) return Optional.empty();
    Set<String> out = new LinkedHashSet<>();
    for (JsonValue item : a) {
      if (item instanceof JsonString s) out.add(s.getString());
    }
    return Optional.of(out);
  }

  private static void refuse(String why) {
    throw invalid(why);
  }

  private static SsoRefused invalid(String why) {
    return new SsoRefused(SsoRefused.DISCOVERY_INVALID, "discovery document refused: " + why);
  }
}
