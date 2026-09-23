package com.storeql.notification.service;

import java.util.Optional;
import java.util.UUID;

/** What a message needs to know about the business sending it. */
public interface Businesses {

  /** Its country, ISO 3166 alpha-2, which decides how amounts and dates are written. */
  Optional<String> country(UUID tenantId);

  /** The name it trades under, which signs its messages. */
  Optional<String> name(UUID tenantId);

  /**
   * Whether it is a sandbox (22.8): a stand-in for a live business where no message may reach a
   * real person. False when unknown — an outage never silences a live business.
   */
  boolean sandbox(UUID tenantId);
}
