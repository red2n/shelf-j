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
}
