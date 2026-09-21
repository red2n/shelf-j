package com.storeql.iam.sso;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses single sign-on may call. The issuer is typed by a business and every other
 * address comes from a document the issuer serves, while this service sits inside the cluster
 * beside the database, the other services and a cloud's metadata endpoint. So a provider is HTTPS,
 * on a public address, with no credentials in the URL — unless the deployment names its host as one
 * of its own, as local development does for the provider it runs beside the stack.
 *
 * <p>The host is resolved and every address it resolves to is checked. A name that resolves
 * elsewhere between this check and the connection (DNS rebinding) is not caught here; the network
 * policy that keeps iam-svc's egress to the internet is what covers that.
 */
public final class Egress {

  /** Resolves a host name; replaceable in tests. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Set<String> insecureHosts;
  private final Resolver resolver;

  /**
   * @param insecureHosts host names, lower case, reachable over plain HTTP and at a private address
   */
  public Egress(Set<String> insecureHosts, Resolver resolver) {
    this.insecureHosts = Set.copyOf(insecureHosts);
    this.resolver = resolver;
  }

  public Egress(Set<String> insecureHosts) {
    this(insecureHosts, InetAddress::getAllByName);
  }

  /**
   * The URL, if it may be called.
   *
   * @throws SsoRefused {@link SsoRefused#ADDRESS_REFUSED} when it may not
   */
  public URI check(String url) {
    if (url == null) throw refused("no URL");
    URI uri;
    try {
      uri = new URI(url);
    } catch (java.net.URISyntaxException e) {
      throw new SsoRefused(SsoRefused.ADDRESS_REFUSED, "provider address refused: not a URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) throw refused("no host");
    if (uri.getRawUserInfo() != null) throw refused("credentials in the URL");
    if (uri.getRawFragment() != null) throw refused("a fragment");
    boolean own = insecureHosts.contains(host);
    if (!"https".equals(scheme) && !(own && "http".equals(scheme))) {
      throw refused("not HTTPS");
    }
    if (own) return uri;
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new SsoRefused(SsoRefused.UNREACHABLE, "the host " + host + " does not resolve", e);
    }
    for (InetAddress a : addresses) {
      if (internal(a)) throw refused(host + " resolves to an address inside the network");
    }
    return uri;
  }

  /** Loopback, private, link-local, unique-local, multicast or unspecified. */
  static boolean internal(InetAddress a) {
    if (a.isLoopbackAddress()
        || a.isAnyLocalAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return true;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet6Address) {
      // fc00::/7, unique local — IPv6's private range, which isSiteLocalAddress does not cover.
      return (b[0] & 0xfe) == 0xfc;
    }
    // 100.64.0.0/10, carrier-grade NAT; and 0.0.0.0/8.
    return ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) || b[0] == 0;
  }

  private static SsoRefused refused(String why) {
    return new SsoRefused(SsoRefused.ADDRESS_REFUSED, "provider address refused: " + why);
  }
}
