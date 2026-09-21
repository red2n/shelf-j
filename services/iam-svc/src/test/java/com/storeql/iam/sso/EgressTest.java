package com.storeql.iam.sso;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The issuer is typed by a business and iam-svc sits inside the cluster: what a provider address
 * may be, and what it may resolve to.
 */
class EgressTest {

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A resolver that answers from a table, so no test depends on real DNS. */
  private static Egress egress(Set<String> own, Map<String, String> dns) {
    return new Egress(
        own,
        host -> {
          String a = dns.get(host);
          if (a == null) throw new UnknownHostException(host);
          return new InetAddress[] {ip(a)};
        });
  }

  private static String refusal(Egress e, String url) {
    return assertThrows(SsoRefused.class, () -> e.check(url)).code();
  }

  @Test
  @DisplayName("A provider on a public address over HTTPS is called")
  void aPublicHttpsProviderIsCalled() {
    Egress e = egress(Set.of(), Map.of("login.example.com", "93.184.216.34"));
    assertThat(e.check("https://login.example.com/tenant/v2.0").getHost(), is("login.example.com"));
  }

  @Test
  @DisplayName("Plain HTTP, credentials in the URL, a fragment, or no host: refused")
  void theShapeIsHeldFirst() {
    Egress e = egress(Set.of(), Map.of("login.example.com", "93.184.216.34"));
    assertThat(refusal(e, "http://login.example.com"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, "https://user:pw@login.example.com"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, "https://login.example.com/#x"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, "file:///etc/passwd"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, "not a url at all"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, null), is(SsoRefused.ADDRESS_REFUSED));
  }

  @Test
  @DisplayName("A name that resolves inside the network is refused, whatever it is called")
  void internalAddressesAreRefused() {
    Egress e =
        egress(
            Set.of(),
            Map.of(
                "metadata.example.com", "169.254.169.254",
                "db.example.com", "10.0.0.5",
                "consul.example.com", "172.18.0.4",
                "home.example.com", "192.168.1.1",
                "loop.example.com", "127.0.0.1",
                "v6loop.example.com", "::1",
                "v6private.example.com", "fd12:3456::1",
                "cgnat.example.com", "100.64.3.2",
                "zero.example.com", "0.0.0.0"));
    for (String host :
        new String[] {
          "metadata.example.com",
          "db.example.com",
          "consul.example.com",
          "home.example.com",
          "loop.example.com",
          "v6loop.example.com",
          "v6private.example.com",
          "cgnat.example.com",
          "zero.example.com"
        }) {
      assertThat(host, refusal(e, "https://" + host + "/"), is(SsoRefused.ADDRESS_REFUSED));
    }
    assertThat(
        "an address typed as a literal is judged the same",
        refusal(
            egress(Set.of(), Map.of("169.254.169.254", "169.254.169.254")),
            "https://169.254.169.254/"),
        is(SsoRefused.ADDRESS_REFUSED));
  }

  @Test
  @DisplayName("A name that does not resolve is unreachable, not refused")
  void anUnknownNameIsUnreachable() {
    assertThat(
        refusal(egress(Set.of(), Map.of()), "https://nowhere.example.com"),
        is(SsoRefused.UNREACHABLE));
  }

  @Test
  @DisplayName("A host the deployment names as its own is reached over HTTP, and only that host")
  void theDeploymentsOwnProviderIsReached() {
    Egress e = egress(Set.of("mock-idp"), Map.of("other", "10.0.0.9"));
    assertThat(e.check("http://mock-idp:8080/storeql").getPort(), is(8080));
    assertThat(refusal(e, "http://other:8080/"), is(SsoRefused.ADDRESS_REFUSED));
    assertThat(refusal(e, "https://other/"), is(SsoRefused.ADDRESS_REFUSED));
  }
}
