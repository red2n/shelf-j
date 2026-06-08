package com.shelfj.discovery;

/**
 * A discovered, healthy instance of a service: where to reach it.
 *
 * @param serviceName logical name (e.g. {@code "sample-svc"})
 * @param host reachable host/IP
 * @param port reachable port
 */
public record ServiceInstance(String serviceName, String host, int port) {

  /** Base URL to reach this instance, e.g. {@code http://10.0.0.4:8000}. */
  public String baseUri() {
    return "http://" + host + ":" + port;
  }
}
