package com.shelfj.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantProfilesTest {

  private static final UUID TENANT = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478e1");

  private static String body(String currency, String country) {
    return "{\"data\":{\"id\":\""
        + TENANT
        + "\",\"currency\":"
        + json(currency)
        + ",\"country\":"
        + json(country)
        + "}}";
  }

  private static String json(String v) {
    return v == null ? "null" : "\"" + v + "\"";
  }

  @Test
  @DisplayName("A tenant's own currency and country are read, trimmed and upper-cased")
  void readsTheTenantsOwnProfile() {
    var p = TenantProfiles.parse(TENANT, body(" jpy ", "jp")).orElseThrow();
    assertEquals("JPY", p.currency());
    assertEquals("JP", p.country());
    assertEquals(TENANT, p.tenantId());
    assertEquals("KWD", TenantProfiles.parse(TENANT, body("KWD", "KW")).orElseThrow().currency());
  }

  @Test
  @DisplayName("Anything short of a real code is no profile, never a guess")
  void refusesWhatIsNotAProfile() {
    for (String bad :
        new String[] {
          body(null, "JP"),
          body("JP", "JP"),
          body("JPYX", "JP"),
          body("J1Y", "JP"),
          body("JPY", null),
          body("JPY", "JPN"),
          body("JPY", ""),
          "{\"data\":null}",
          "{}",
          "{not json",
          "[]",
          "{\"data\":{\"currency\":\"';--\",\"country\":\"JP\"}}"
        }) {
      assertTrue(TenantProfiles.parse(TENANT, bad).isEmpty(), bad);
    }
  }

  @Test
  @DisplayName("A profile is cached for five minutes; a failed read is not cached")
  void cachesWhatItReadsAndNotWhatItCouldNot() {
    AtomicInteger calls = new AtomicInteger();
    Instant[] now = {Instant.parse("2026-09-13T10:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    boolean[] up = {false};
    var profiles =
        TenantProfiles.forTest(
            id -> {
              calls.incrementAndGet();
              return up[0] ? Optional.of(body("JPY", "JP")) : Optional.empty();
            },
            clock);

    // tenant-svc down: refused, not guessed, and asked again next time
    var down = assertThrows(ApiException.class, () -> profiles.requireCurrency(TENANT));
    assertEquals(503, down.status());
    assertEquals("TENANT_PROFILE_UNAVAILABLE", down.code());
    assertTrue(profiles.find(TENANT).isEmpty());
    assertEquals(2, calls.get());

    up[0] = true;
    assertEquals("JPY", profiles.requireCurrency(TENANT));
    assertEquals("JP", profiles.requireCountry(TENANT));
    for (int i = 0; i < 100; i++) profiles.find(TENANT);
    assertEquals(3, calls.get());

    now[0] = now[0].plus(TenantProfiles.TTL).plusSeconds(1);
    profiles.find(TENANT);
    assertEquals(4, calls.get());
    assertTrue(profiles.find(null).isEmpty());
  }

  @Test
  @DisplayName("A named currency or country must be a real code; a blank one is the tenant's own")
  void namedCodesAreValidatedAndBlankMeansTheTenants() {
    var profiles =
        TenantProfiles.forTest(id -> Optional.of(body("JPY", "JP")), java.time.Clock.systemUTC());
    assertEquals("JPY", profiles.currencyOr(TENANT, null));
    assertEquals("JPY", profiles.currencyOr(TENANT, "   "));
    assertEquals("EUR", profiles.currencyOr(TENANT, " eur "));
    assertEquals("KWD", profiles.currencyOr(TENANT, "KWD"));
    assertEquals("JP", profiles.countryOr(TENANT, ""));
    assertEquals("IE", profiles.countryOr(TENANT, "ie"));
    for (String bad : new String[] {"12", "POUNDS", "';-", "<B>", "GB P", "ZZZ", "£"}) {
      var e = assertThrows(ApiException.class, () -> profiles.currencyOr(TENANT, bad), bad);
      assertEquals(400, e.status());
      assertEquals("CURRENCY_INVALID", e.code());
    }
    for (String bad : new String[] {"GBR", "Z1", "UK", "<", "ZZ"}) {
      var e = assertThrows(ApiException.class, () -> profiles.countryOr(TENANT, bad), bad);
      assertEquals("COUNTRY_INVALID", e.code());
    }
  }

  private static final UUID STORE_DE = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f1");
  private static final UUID STORE_NONE = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f2");
  private static final UUID STORE_NEW = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f3");

  /** A clock the store tests move by hand. */
  private static final class Moving extends Clock {
    Instant now = Instant.parse("2026-09-14T09:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static String storesPage(String next, String... rows) {
    return "{\"data\":["
        + String.join(",", rows)
        + "],\"meta\":{\"nextCursor\":"
        + json(next)
        + "}}";
  }

  private static String store(UUID id, String country) {
    return "{\"id\":\"" + id + "\",\"country\":" + json(country) + "}";
  }

  @Test
  @DisplayName(
      "Every page of stores is read; each store's country kept, upper-cased; none recorded is none")
  void storesAreReadPageByPage() {
    AtomicInteger reads = new AtomicInteger();
    Moving clock = new Moving();
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) -> {
              reads.incrementAndGet();
              return Optional.of(
                  after == null
                      ? storesPage("c1", store(STORE_DE, " de "))
                      : storesPage(null, store(STORE_NONE, null)));
            },
            clock);
    var stores = profiles.stores(TENANT, null);
    assertEquals(java.util.Set.of(STORE_DE, STORE_NONE), stores.ids());
    assertEquals(java.util.Map.of(STORE_DE, "DE"), stores.countries());
    assertEquals(2, reads.get());

    profiles.stores(TENANT, STORE_DE);
    assertEquals(2, reads.get(), "a known store is served from the cache");

    profiles.stores(TENANT, STORE_NEW);
    assertEquals(2, reads.get(), "an unknown store does not force a read inside the re-read gap");
    clock.now = clock.now.plus(TenantProfiles.REREAD).plusSeconds(1);
    profiles.stores(TENANT, STORE_NEW);
    assertEquals(4, reads.get(), "after the gap an unknown store is looked for once");
    profiles.stores(TENANT, STORE_NEW);
    assertEquals(4, reads.get(), "and not again straight away, however often it is asked for");

    clock.now = clock.now.plus(TenantProfiles.TTL);
    profiles.stores(TENANT, null);
    assertEquals(6, reads.get(), "the cache ages out");
  }

  @Test
  @DisplayName("Stores that cannot be read are refused, never taken to be none, and not cached")
  void unreadableStoresAreRefused() {
    AtomicInteger reads = new AtomicInteger();
    for (String bad :
        new String[] {
          null,
          "{not json",
          "{}",
          "{\"data\":null}",
          "{\"data\":[{\"country\":\"DE\"}]}",
          "{\"data\":[{\"id\":\"x' OR '1'='1\",\"country\":\"DE\"}]}"
        }) {
      var profiles =
          TenantProfiles.forTest(
              id -> Optional.empty(),
              (tenant, after) -> {
                reads.incrementAndGet();
                return Optional.ofNullable(bad);
              },
              Clock.systemUTC());
      ApiException e = assertThrows(ApiException.class, () -> profiles.stores(TENANT, null));
      assertEquals(503, e.status(), String.valueOf(bad));
      assertEquals("TENANT_STORES_UNAVAILABLE", e.code());
      int before = reads.get();
      assertThrows(ApiException.class, () -> profiles.stores(TENANT, null));
      assertTrue(reads.get() > before, "a failure is not cached: " + bad);
    }
    var endless =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) -> Optional.of(storesPage("again", store(STORE_DE, "DE"))),
            Clock.systemUTC());
    assertThrows(ApiException.class, () -> endless.stores(TENANT, null));
  }
}
