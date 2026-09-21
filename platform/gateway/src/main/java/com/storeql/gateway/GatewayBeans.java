package com.storeql.gateway;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceRegistry;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producers for gateway-scoped infrastructure beans. Centralises object construction so {@link
 * ProxyResource} depends only on interfaces/abstractions rather than constructing its own
 * collaborators (DIP).
 */
@ApplicationScoped
class GatewayBeans {

  @Inject GatewayConfig config;

  @Produces
  @ApplicationScoped
  ServiceRegistry serviceRegistry() {
    return new ConsulClient(config.consulHost(), config.consulPort());
  }

  @Produces
  @ApplicationScoped
  WebClient proxyWebClient() {
    // Bounded waits: without timeouts one hung upstream pins gateway requests indefinitely.
    //
    // Redirects are the browser's to follow, not the gateway's. A service answers one when it
    // sends the browser somewhere — iam-svc's single sign-on callback sending it back to the app —
    // and a gateway that followed it would fetch the app itself from inside the cluster and hand
    // the browser the page with a 200, in place of the redirect it was meant to take.
    return WebClient.builder()
        .connectTimeout(java.time.Duration.ofSeconds(config.upstreamConnectTimeoutSeconds()))
        .readTimeout(java.time.Duration.ofSeconds(config.upstreamReadTimeoutSeconds()))
        .followRedirects(false)
        .build();
  }
}
