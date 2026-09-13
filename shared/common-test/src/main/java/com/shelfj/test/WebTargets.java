package com.shelfj.test;

import jakarta.ws.rs.client.WebTarget;

/** Request-building helpers shared by the services' integration tests. */
public final class WebTargets {

  private WebTargets() {}

  /**
   * Resolves a path that may carry a query string against a target. {@code WebTarget.path} encodes
   * a {@code ?} rather than parsing it, so a test that writes {@code "/x?a=1&b=2"} the way a client
   * would needs the query split into {@code queryParam} calls.
   *
   * @param root the test's injected target
   * @param pathAndQuery a path, optionally followed by {@code ?name=value&...}
   * @return the target at that path with the query applied
   */
  public static WebTarget at(WebTarget root, String pathAndQuery) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = root.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t;
  }
}
