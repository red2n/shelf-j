package com.storeql.iam.sso;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a discovery document must say before a sign-in is sent anywhere it names. */
class DiscoveryTest {

  private static final String ISSUER = "https://login.example.com/t/v2.0";

  private static String doc(String issuer, String extra) {
    return "{\"issuer\":\""
        + issuer
        + "\",\"authorization_endpoint\":\"https://login.example.com/authorize\","
        + "\"token_endpoint\":\"https://login.example.com/token\","
        + "\"jwks_uri\":\"https://login.example.com/keys\""
        + extra
        + "}";
  }

  private static String refusal(String json) {
    return assertThrows(SsoRefused.class, () -> Discovery.parse(json, ISSUER)).getMessage();
  }

  @Test
  @DisplayName("Found at its path under the issuer, whether the issuer ends in a slash or not")
  void itsLocation() {
    assertThat(
        Discovery.location("https://a.example.com/x"),
        is("https://a.example.com/x/.well-known/openid-configuration"));
    assertThat(
        Discovery.location("https://a.example.com/x/"),
        is("https://a.example.com/x/.well-known/openid-configuration"));
  }

  @Test
  @DisplayName("A minimal document: basic client authentication, no userinfo")
  void aMinimalDocument() {
    Discovery d = Discovery.parse(doc(ISSUER, ""), ISSUER);
    assertThat(d.tokenAuthMethod(), is(Discovery.BASIC));
    assertThat(d.userinfoEndpoint().isPresent(), is(false));
  }

  @Test
  @DisplayName("A provider that takes the secret only in the body is sent it there")
  void secretInTheBody() {
    Discovery d =
        Discovery.parse(
            doc(
                ISSUER,
                ",\"token_endpoint_auth_methods_supported\":[\"private_key_jwt\","
                    + "\"client_secret_post\"],\"userinfo_endpoint\":\"https://login.example.com/me\""),
            ISSUER);
    assertThat(d.tokenAuthMethod(), is(Discovery.POST));
    assertThat(d.userinfoEndpoint().orElseThrow(), is("https://login.example.com/me"));
  }

  @Test
  @DisplayName("A document naming another issuer does not speak for this one")
  void anotherIssuerIsRefused() {
    assertThat(refusal(doc("https://evil.example.com", "")).contains("names the issuer"), is(true));
    assertThat(
        "even a trailing slash is another issuer",
        refusal(doc(ISSUER + "/", "")).contains("names the issuer"),
        is(true));
  }

  @Test
  @DisplayName("A provider that cannot do what this client does is refused, and says why")
  void whatItCannotDoIsRefused() {
    assertThat(
        refusal(doc(ISSUER, ",\"response_types_supported\":[\"id_token\"]"))
            .contains("authorization code"),
        is(true));
    assertThat(
        refusal(doc(ISSUER, ",\"id_token_signing_alg_values_supported\":[\"HS256\"]"))
            .contains("RS256"),
        is(true));
    assertThat(
        refusal(doc(ISSUER, ",\"code_challenge_methods_supported\":[\"plain\"]")).contains("S256"),
        is(true));
    assertThat(
        refusal(doc(ISSUER, ",\"token_endpoint_auth_methods_supported\":[\"private_key_jwt\"]"))
            .contains("client_secret"),
        is(true));
  }

  @Test
  @DisplayName("Missing endpoints, or not JSON at all")
  void brokenDocuments() {
    assertThat(
        refusal("{\"issuer\":\"" + ISSUER + "\"}").contains("authorization_endpoint"), is(true));
    assertThat(refusal("<html>hello</html>").contains("not a JSON object"), is(true));
    assertThat(refusal("[]").contains("not a JSON object"), is(true));
    assertThat(refusal("{}").contains("no issuer"), is(true));
  }
}
