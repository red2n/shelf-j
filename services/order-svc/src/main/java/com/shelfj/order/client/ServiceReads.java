package com.shelfj.order.client;

import com.shelfj.order.config.ServiceConfig;
import com.shelfj.service.ServiceReader;
import com.shelfj.service.ServiceReader.Reply;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads other services' answers as a member of staff, for the documents order-svc writes about a
 * sale after the fact (18.9): the buyer's VAT status, the customer's billing address, the items'
 * names and HSN codes, the store's address. One {@link ServiceReader} per service, found through
 * Consul or at {@code shelfj.clients.<service>.url}.
 */
@ApplicationScoped
public class ServiceReads {

  /** Staff: every read here is one a manager's screen makes. */
  static final String INTERNAL_ROLE = "MANAGER";

  private static final int ATTEMPTS = 2;

  @Inject ServiceConfig config;

  private final Map<String, ServiceReader> readers = new ConcurrentHashMap<>();

  /**
   * A GET, as its status and body.
   *
   * @param service the service name, as Consul and the override property know it
   * @param path the path, starting with a slash
   * @param query query parameters
   */
  public Reply get(String service, UUID tenantId, String path, Map<String, String> query) {
    return readers
        .computeIfAbsent(
            service,
            s ->
                new ServiceReader(
                    config, s, INTERNAL_ROLE, ATTEMPTS, ServiceReader.configuredUrl(s)))
        .get(tenantId, path, query);
  }
}
