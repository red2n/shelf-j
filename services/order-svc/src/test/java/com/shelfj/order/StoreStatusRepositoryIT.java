package com.shelfj.order;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.service.StoreStatusRepository;
import com.shelfj.test.PostgresSupport;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Postgres coverage for the tenant-aware store check (see StoreStatusRepository#isActive):
 * a store projected under one tenant must never read as active for a different tenant, even
 * though a never-seen store id still fails open. Lives in order-svc rather than common-service
 * because that's where this exact PostgresSupport + Flyway pattern is already proven (see
 * OrderIT), and order-svc owns a real copy of the store_status table
 * (V5__flow_guard_projections.sql) — common-service itself carries no schema for it.
 */
class StoreStatusRepositoryIT {

  private static final PostgresSupport PG;
  private static final StoreStatusRepository REPO = new StoreStatusRepository();

  static {
    PG = PostgresSupport.start().migrate("classpath:db/migration");
    try {
      Field f = com.shelfj.service.BaseJdbcRepository.class.getDeclaredField("dataSource");
      f.setAccessible(true);
      f.set(REPO, PG.dataSource());
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void cleanTable() throws Exception {
    try (var c = PG.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("TRUNCATE TABLE store_status");
    }
  }

  @Test
  void neverProjectedStoreFailsOpen() {
    assertTrue(
        REPO.isActive(TENANT_A, UUID.randomUUID()),
        "a store this projection has never heard about must fail open (Kafka ordering race)");
  }

  @Test
  void activeStoreUnderTheRightTenantPasses() {
    UUID store = UUID.randomUUID();
    REPO.upsertStoreStatus(store, TENANT_A, "ACTIVE", Instant.now());
    assertTrue(REPO.isActive(TENANT_A, store));
  }

  @Test
  void suspendedStoreUnderTheRightTenantFails() {
    UUID store = UUID.randomUUID();
    REPO.upsertStoreStatus(store, TENANT_A, "SUSPENDED", Instant.now());
    assertFalse(REPO.isActive(TENANT_A, store));
  }

  @Test
  void activeStoreUnderADifferentTenantIsRejectedRegardlessOfStatus() {
    UUID store = UUID.randomUUID();
    // TENANT_B's store, genuinely ACTIVE — but TENANT_A has no claim to it.
    REPO.upsertStoreStatus(store, TENANT_B, "ACTIVE", Instant.now());
    assertFalse(
        REPO.isActive(TENANT_A, store),
        "a real, active store belonging to a different tenant must never pass — this is the"
            + " exact cross-tenant reference the fix closes");
    // Sanity check: it's still correctly active for its actual owner.
    assertTrue(REPO.isActive(TENANT_B, store));
  }
}
