package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.ids.Ids;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Finding #5 — the per-tenant active/inactive cache must be bounded, not grow forever. */
@ExtendWith(MockitoExtension.class)
class TenantStatusGateTest {

  @Mock ServiceRegistry registry;

  private TenantStatusGate gate;

  @BeforeEach
  void setUp() {
    gate = new TenantStatusGate();
    gate.registry = registry;
    // tenant-svc unresolvable -> lookup() fails open without ever touching webClient.
    when(registry.resolve(any())).thenReturn(Optional.empty());
  }

  @Test
  void cacheNeverGrowsPastTheHardCapUnderTenantIdChurn() {
    String[] ids = new String[TenantStatusGate.MAX_ENTRIES + 5];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = Ids.newId().toString();
      gate.isActive(ids[i]);
    }
    verify(registry, times(ids.length)).resolve("tenant-svc");

    // Re-querying every id ever seen: if the cache were unbounded, all of them would still be
    // cached and no further lookups would fire. A bounded cache must have evicted some of them
    // to stay at the cap, so at least one of these re-queries is a fresh lookup.
    for (String id : ids) {
      gate.isActive(id);
    }
    verify(registry, atLeast(ids.length + 1)).resolve("tenant-svc");
  }

  @Test
  void activeAnswerIsCachedAndDoesNotRequeryWithinTtl() {
    String id = Ids.newId().toString();

    gate.isActive(id);
    gate.isActive(id);
    gate.isActive(id);

    verify(registry, times(1)).resolve("tenant-svc");
  }

  @Test
  void failsOpenWhenTenantServiceIsUnresolvable() {
    assertTrue(gate.isActive(Ids.newId().toString()));
  }
}
