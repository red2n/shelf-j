package com.shelfj.tenant.messaging;

import com.shelfj.tenant.service.SwitchingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Records one service's {@code TenantDataErased} as evidence, once per announcement (21.14). A
 * malformed payload, or one answering no erasure started here, is skipped by the service; a write
 * failure propagates so the consumer loop reads the record again.
 */
@ApplicationScoped
class TenantDataErasedHandler {

  @Inject SwitchingService service;

  boolean handle(String json) {
    return service.recordErased(json);
  }
}
