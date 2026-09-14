package com.shelfj.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JurisdictionsTest {

  private static final UUID TENANT = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478e2");

  /** A clock a test can move. */
  private static final class Hands extends Clock {
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

  private static final String RULES =
      "{\"data\":{\"country\":\"GB\",\"obligations\":["
          + "{\"code\":\"GDPR\",\"scope\":\"EU\",\"effectiveFrom\":\"2018-05-25\",\"effectiveTo\":\"2020-01-31\"},"
          + "{\"code\":\"TOBACCO_BIRTH_COHORT\",\"scope\":\"GB\",\"effectiveFrom\":\"2027-01-01\"}]}}";

  private static TenantProfiles profiles(String country) {
    return TenantProfiles.forTest(
        id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"" + country + "\"}}"),
        Clock.systemUTC());
  }

  @Test
  @DisplayName("An obligation binds from its first day, not the day before")
  void inForceFromItsDay() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertFalse(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2026, 12, 31)));
    assertTrue(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2027, 1, 1)));
    assertTrue(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2040, 6, 1)));
    // A code the rules do not carry binds nobody.
    assertFalse(j.inForce(TENANT, "NOT_A_LAW", LocalDate.of(2040, 6, 1)));
  }

  @Test
  @DisplayName("A window that closed ends the obligation on its last day")
  void anEndedWindowEnds() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertTrue(j.inForce(TENANT, "GDPR", LocalDate.of(2020, 1, 31)));
    assertFalse(j.inForce(TENANT, "GDPR", LocalDate.of(2020, 2, 1)));
    assertFalse(j.inForce(TENANT, "GDPR", LocalDate.of(2018, 5, 24)));
  }

  @Test
  @DisplayName("The tenant's own country is asked about, and a store's may be named instead")
  void theCountryAskedAbout() {
    List<String> asked = new ArrayList<>();
    var j =
        Jurisdictions.forTest(
            profiles("DE"),
            (t, c) -> {
              asked.add(c);
              return Optional.of(RULES);
            },
            new Hands());
    j.inForce(TENANT, "GDPR", LocalDate.of(2026, 1, 1));
    j.inForceIn(TENANT, " fr ", "GDPR", LocalDate.of(2026, 1, 1));
    assertEquals(List.of("DE", "FR"), asked);
  }

  @Test
  @DisplayName("A country's rules are cached for an hour; a failed read is not cached")
  void cachedPerCountryButNotFailures() {
    Hands hands = new Hands();
    int[] reads = {0};
    boolean[] down = {true};
    var j =
        Jurisdictions.forTest(
            profiles("GB"),
            (t, c) -> {
              reads[0]++;
              return down[0] ? Optional.empty() : Optional.of(RULES);
            },
            hands);
    assertThrows(ApiException.class, () -> j.obligations(TENANT, "GB"));
    down[0] = false;
    j.obligations(TENANT, "GB");
    j.obligations(TENANT, "GB");
    assertEquals(2, reads[0]);
    hands.now = hands.now.plus(Jurisdictions.TTL).plusSeconds(1);
    j.obligations(TENANT, "GB");
    assertEquals(3, reads[0]);
  }

  @Test
  @DisplayName("Rules that cannot be read are refused, never assumed absent")
  void unreadableRulesAreRefused() {
    for (String bad :
        new String[] {
          "not json",
          "{\"data\":null}",
          "{\"data\":{\"country\":\"GB\"}}",
          "{\"data\":{\"obligations\":[{\"code\":\"GDPR\",\"effectiveFrom\":\"soon\"}]}}",
          "{\"data\":{\"obligations\":[{\"scope\":\"EU\",\"effectiveFrom\":\"2018-05-25\"}]}}"
        }) {
      var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(bad), new Hands());
      ApiException e =
          assertThrows(
              ApiException.class, () -> j.inForce(TENANT, "GDPR", LocalDate.of(2019, 1, 1)), bad);
      assertEquals(503, e.status(), bad);
      assertEquals("OBLIGATIONS_UNAVAILABLE", e.code(), bad);
    }
    // A tenant whose own country cannot be read is refused before any rule is asked for.
    var noProfile =
        Jurisdictions.forTest(
            TenantProfiles.forTest(id -> Optional.empty(), Clock.systemUTC()),
            (t, c) -> Optional.of(RULES),
            new Hands());
    assertEquals(
        "TENANT_PROFILE_UNAVAILABLE",
        assertThrows(
                ApiException.class,
                () -> noProfile.inForce(TENANT, "GDPR", LocalDate.of(2019, 1, 1)))
            .code());
  }

  @Test
  @DisplayName(
      "An offer at a store reaches its country and the business's; with no store, every store's")
  void theCountriesAnOfferReaches() {
    UUID de = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f1");
    UUID none = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f2");
    UUID fr = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f3");
    UUID stranger = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478f4");
    TenantProfiles profiles =
        TenantProfiles.forTest(
            id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"GB\"}}"),
            (t, after) ->
                Optional.of(
                    "{\"data\":[{\"id\":\""
                        + de
                        + "\",\"country\":\"DE\"},{\"id\":\""
                        + none
                        + "\"},{\"id\":\""
                        + fr
                        + "\",\"country\":\"FR\"}],\"meta\":{}}"),
            Clock.systemUTC());
    String euOnly =
        "{\"data\":{\"obligations\":[{\"code\":\"PRICE_REDUCTION_PRIOR_PRICE\",\"scope\":\"EU\","
            + "\"effectiveFrom\":\"2022-05-28\"}]}}";
    var j =
        Jurisdictions.forTest(
            profiles,
            (t, c) -> Optional.of("GB".equals(c) ? "{\"data\":{\"obligations\":[]}}" : euOnly),
            new Hands());
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE", "FR"), j.countriesTrading(TENANT, null));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE"), j.countriesTrading(TENANT, de));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB"), j.countriesTrading(TENANT, none));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE", "FR"),
        j.countriesTrading(TENANT, stranger),
        "a store that is not the business's cannot narrow the law to the business's own country");

    LocalDate day = LocalDate.of(2026, 9, 14);
    assertTrue(j.inForceWhereTrading(TENANT, de, "PRICE_REDUCTION_PRIOR_PRICE", day));
    assertFalse(j.inForceWhereTrading(TENANT, none, "PRICE_REDUCTION_PRIOR_PRICE", day));
    assertTrue(
        j.inForceWhereTrading(TENANT, null, "PRICE_REDUCTION_PRIOR_PRICE", day),
        "online, a British business with a German shop is bound");
    assertFalse(
        j.inForce(TENANT, "PRICE_REDUCTION_PRIOR_PRICE", day),
        "the business's own country alone is unchanged");
  }

  @Test
  @DisplayName("Stores that cannot be read refuse the question rather than narrow it")
  void unreadableStoresRefuse() {
    TenantProfiles profiles =
        TenantProfiles.forTest(
            id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"GB\"}}"),
            (t, after) -> Optional.empty(),
            Clock.systemUTC());
    var j = Jurisdictions.forTest(profiles, (t, c) -> Optional.of(RULES), new Hands());
    ApiException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () -> j.inForceWhereTrading(TENANT, null, "GDPR", LocalDate.of(2019, 1, 1)));
    org.junit.jupiter.api.Assertions.assertEquals(503, e.status());
  }
}
