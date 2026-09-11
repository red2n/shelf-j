package com.shelfj.notification.service;

import com.shelfj.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Erasing the messages sent about a person (SJ-D43). */
@ApplicationScoped
public class NotificationErasure {

  @Inject NotificationRepository repo;

  /** A shop erased one of its customers: that shop's messages about them. */
  public int customerErased(UUID tenantId, UUID customerId) {
    return repo.redactForCustomer(tenantId, customerId);
  }

  /** A person deleted their account: the platform's own messages about it. */
  public int accountDeleted(UUID userId) {
    return repo.redactForAccount(userId);
  }
}
