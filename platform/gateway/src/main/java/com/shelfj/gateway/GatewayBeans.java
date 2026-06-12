package com.shelfj.gateway;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceRegistry;
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
    return WebClient.builder()
        .connectTimeout(java.time.Duration.ofSeconds(config.upstreamConnectTimeoutSeconds()))
        .readTimeout(java.time.Duration.ofSeconds(config.upstreamReadTimeoutSeconds()))
        .build();
  }
}
