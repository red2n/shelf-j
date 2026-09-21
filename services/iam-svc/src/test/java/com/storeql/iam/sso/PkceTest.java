package com.storeql.iam.sso;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PkceTest {

  @Test
  @DisplayName("RFC 7636 appendix B: the verifier and challenge the RFC itself gives")
  void theRfcsOwnExample() {
    assertThat(
        Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        is("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
    assertThat(
        Pkce.matches(
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
        is(true));
  }

  @Test
  @DisplayName("A verifier is 43 characters of the unreserved set, and new each time")
  void aNewVerifierIsWellFormedAndFresh() {
    String v = Pkce.newVerifier();
    assertThat(v.length(), is(43));
    assertThat(Pkce.wellFormed(v), is(true));
    assertThat(Pkce.newVerifier(), not(v));
  }

  @Test
  @DisplayName("Another verifier, a short one, a strange one or none matches nothing")
  void onlyTheVerifierMatches() {
    String v = Pkce.newVerifier();
    String c = Pkce.challenge(v);
    assertThat(Pkce.matches(Pkce.newVerifier(), c), is(false));
    assertThat(Pkce.matches("short", Pkce.challenge("short")), is(false));
    assertThat(Pkce.matches(v + " ", c), is(false));
    assertThat(Pkce.matches(null, c), is(false));
    assertThat(Pkce.matches(v, null), is(false));
    assertThat(Pkce.matches("a".repeat(129), Pkce.challenge("a".repeat(129))), is(false));
  }
}
