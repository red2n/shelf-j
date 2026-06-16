package com.shelfj.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Domain {

  private Domain() {}

  public record ShortageAlert(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal available,
      BigDecimal threshold,
      UUID eventId,
      Instant alertedAt) {}
}
