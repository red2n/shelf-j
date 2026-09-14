package com.shelfj.service;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How common-service reads tenant-svc on a tenant's behalf, for {@link TenantProfiles} and {@link
 * Jurisdictions}: located through Consul (rule #4), or at {@code shelfj.clients.tenant-svc.url}
 * when that is set; the tenant and a staff role stamped on the call; three attempts on a server
 * error or a failed connection, and none repeated on a 4xx.
 */
final class TenantSvcClient {

  private static final Logger LOG = System.getLogger(TenantSvcClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";

  /**
   * The reads under {@code /admin/tenant} sit in the staff-operable tier, so the lowest staff role
   * reaches them; stamped explicitly, for the reason SJ-D13 established — a call carrying no
   * identity is one routing mistake away from an impersonation.
   */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  private static final int ATTEMPTS = 3;

  private final WebClient web;
  private final ConsulClient consul;
  private final Optional<String> configuredBase;

  TenantSvcClient(ServiceSettings settings, Optional<String> tenantSvcUrl) {
    web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    consul =
        settings.consulEnabled()
            ? new ConsulClient(settings.consulHost(), settings.consulPort())
            : null;
    configuredBase = tenantSvcUrl.filter(u -> !u.isBlank());
  }

  /**
   * A GET under the tenant's identity.
   *
   * @param query query parameters, sent as parameters rather than folded into the path
   * @return the body of a 200; empty when tenant-svc cannot be located, refuses, or keeps failing
   */
  Optional<String> get(UUID tenantId, String path, Map<String, String> query) {
    String base = configuredBase.orElse(null);
    if (base == null && consul != null) {
      base = consul.resolve(TENANT_SERVICE).map(ServiceInstance::baseUri).orElse(null);
    }
    if (base == null) {
      LOG.log(Level.WARNING, "tenant-svc could not be located for {0} of {1}", path, tenantId);
      return Optional.empty();
    }
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      HttpClientRequest request =
          web.get(base + path)
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE);
      for (Map.Entry<String, String> param : query.entrySet()) {
        request = request.queryParam(param.getKey(), param.getValue());
      }
      try (HttpClientResponse res = request.request()) {
        int status = res.status().code();
        if (status == 200) return Optional.of(res.as(String.class));
        LOG.log(Level.WARNING, "{0} for {1}: HTTP {2}", path, tenantId, status);
        if (status < 500) return Optional.empty();
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "{0} for {1} failed: {2}", path, tenantId, e.getMessage());
      }
    }
    return Optional.empty();
  }
}
