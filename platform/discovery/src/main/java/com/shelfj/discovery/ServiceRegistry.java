package com.shelfj.discovery;

import java.util.Optional;

/**
 * Abstraction for upstream service discovery. Callers depend on this interface, not on the concrete
 * {@link ConsulClient}, so the lookup mechanism can change without touching the gateway proxy
 * (DIP).
 */
public interface ServiceRegistry {
  Optional<ServiceInstance> resolve(String serviceName);
}
