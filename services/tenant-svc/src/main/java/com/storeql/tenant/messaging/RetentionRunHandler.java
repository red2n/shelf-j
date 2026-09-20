package com.storeql.tenant.messaging;

import com.storeql.tenant.service.RetentionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Puts one purge a service announced ({@code RetentionRunCompleted}) on the register (21.16), once
 * per event id. A malformed payload, or one naming a class this service does not know, is skipped
 * by the service with a warning; a write failure propagates so the consumer loop redelivers.
 */
@ApplicationScoped
class RetentionRunHandler {

  @Inject RetentionService service;

  /**
   * @return whether the run was recorded now
   */
  boolean handle(String json) {
    return service.recordRun(json);
  }
}
