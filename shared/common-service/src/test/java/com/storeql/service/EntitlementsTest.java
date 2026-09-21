package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What a business is allowed (21.8), as the service that has to enforce a limit reads it: the
 * ceiling is applied where there is one, the count is paid for only when it matters, and everything
 * that could go wrong — no plan, an unreadable answer, a service that cannot be reached — leaves
 * the business unrestricted rather than stopped.
 */
class EntitlementsTest {

  private static final UUID TENANT = Ids.newId();

  private static final String ALLOWED =
      "{\"data\":{\"grants\":["
          + "{\"key\":\"stores.max\",\"label\":\"Stores\",\"limitValue\":3},"
          + "{\"key\":\"staff.max\",\"label\":\"Staff\",\"limitValue\":10},"
          + "{\"key\":\"feature.storefront\",\"label\":\"The online shop\",\"enabled\":false}"
          + "]}}";

  private static Clock fixed() {
    return Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC);
  }

  private static Entitlements reading(String body) {
    return Entitlements.forTest(t -> Optional.ofNullable(body), fixed());
  }

  @Test
  void aLimitIsReadAndAFeatureCanBeWithheld() {
    Entitlements e = reading(ALLOWED);

    assertEquals(3L, e.limit(TENANT, Entitlements.STORES_MAX).getAsLong());
    assertEquals(10L, e.limit(TENANT, Entitlements.STAFF_MAX).getAsLong());
    assertTrue(
        e.limit(TENANT, Entitlements.PRODUCTS_MAX).isEmpty(),
        "a key the plan does not name is not a ceiling");
    assertFalse(e.allows(TENANT, Entitlements.FEATURE_STOREFRONT));
    assertTrue(e.allows(TENANT, "feature.nobody.mentioned"), "silence is not a refusal");
  }

  @Test
  void theCountIsAskedForOnlyWhenThereIsACeiling() {
    Entitlements e = reading(ALLOWED);
    AtomicInteger counted = new AtomicInteger();

    e.requireRoom(
        TENANT,
        Entitlements.PRODUCTS_MAX,
        "products",
        () -> {
          counted.incrementAndGet();
          return 999_999L;
        });

    assertEquals(0, counted.get(), "no ceiling, so nothing is counted and nothing is refused");
  }

  @Test
  void underTheCeilingPassesAndAtItRefusesWithTheFigures() {
    Entitlements e = reading(ALLOWED);

    e.requireRoom(TENANT, Entitlements.STORES_MAX, "stores", () -> 2L);

    ApiException refused =
        assertThrows(
            ApiException.class,
            () -> e.requireRoom(TENANT, Entitlements.STORES_MAX, "stores", () -> 3L));
    assertEquals(409, refused.status());
    assertEquals("PLAN_LIMIT_REACHED", refused.code());
    assertTrue(refused.getMessage().contains("allows 3 stores"), refused.getMessage());
    assertTrue(refused.getMessage().contains("has 3"), refused.getMessage());
    // Past it as well as at it: a business moved down a plan is already over, and stays refused.
    assertThrows(
        ApiException.class,
        () -> e.requireRoom(TENANT, Entitlements.STORES_MAX, "stores", () -> 9L));
  }

  @Test
  void everythingThatCanGoWrongLeavesTheBusinessUnrestricted() {
    for (String body :
        new String[] {
          null, // tenant-svc could not be reached
          "", // nothing came back
          "not json at all",
          "{\"data\":null}", // a business on no plan, answered as nothing
          "{\"data\":{}}", // no grants at all
          "{\"data\":{\"grants\":[]}}" // a plan that names nothing
        }) {
      Entitlements e = reading(body);
      String what = body == null ? "unreachable" : body;
      assertTrue(e.limit(TENANT, Entitlements.STORES_MAX).isEmpty(), what);
      assertTrue(e.allows(TENANT, Entitlements.FEATURE_STOREFRONT), what);
      e.requireRoom(TENANT, Entitlements.STORES_MAX, "stores", () -> 10_000L);
    }
  }

  @Test
  void aBusinessIsAskedAboutOnceAWhile() {
    AtomicInteger reads = new AtomicInteger();
    Instant[] now = {Instant.parse("2026-09-17T10:00:00Z")};
    Entitlements e =
        Entitlements.forTest(
            t -> {
              reads.incrementAndGet();
              return Optional.of(ALLOWED);
            },
            Clock.fixed(now[0], ZoneOffset.UTC));

    e.limit(TENANT, Entitlements.STORES_MAX);
    e.limit(TENANT, Entitlements.STAFF_MAX);
    e.allows(TENANT, Entitlements.FEATURE_STOREFRONT);
    assertEquals(1, reads.get(), "three questions about one business, asked once");

    assertTrue(
        e.limit(Ids.newId(), Entitlements.STORES_MAX).isEmpty() || reads.get() == 2,
        "another business is another question");
    assertEquals(2, reads.get());
  }

  @Test
  void aBusinessWithNoTenantIsNobodyToAskAbout() {
    AtomicInteger reads = new AtomicInteger();
    Entitlements e =
        Entitlements.forTest(
            t -> {
              reads.incrementAndGet();
              return Optional.of(ALLOWED);
            },
            fixed());

    assertTrue(e.limit(null, Entitlements.STORES_MAX).isEmpty());
    assertTrue(e.allows(null, Entitlements.FEATURE_STOREFRONT));
    assertEquals(0, reads.get(), "nothing is asked of tenant-svc about nobody");
  }
}
