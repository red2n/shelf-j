package com.shelfj.purchase.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the two cross-service payloads purchase-svc now depends on.
 *
 * <p>These exist because of SJ-D14 and SJ-D20, which were the same finding twice: the only two
 * places in this codebase that parsed another service's DTO inline — behind Consul and a circuit
 * breaker, where no test could reach them — were the two that were wrong, and both stayed wrong for
 * months. The parse is extracted here for no reason other than to be asserted.
 */
class ClientParseTest {

  // ── tenant currency ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A tenant's currency is read out of the envelope's data object")
  void tenantCurrency() {
    for (String c : new String[] {"GBP", "USD", "JPY", "INR", "CNY"}) {
      String body = "{\"data\":{\"id\":\"x\",\"currency\":\"" + c + "\"},\"error\":null}";
      assertThat(TenantClient.parseCurrency(body), is(Optional.of(c)));
    }
  }

  @Test
  @DisplayName("Lower case is normalised — the JWT and the database need not agree on case")
  void tenantCurrencyNormalised() {
    assertThat(
        TenantClient.parseCurrency("{\"data\":{\"currency\":\" jpy \"}}"), is(Optional.of("JPY")));
  }

  @Test
  @DisplayName("SJ-D14: an absent currency key is empty, not an exception")
  void tenantCurrencyAbsent() {
    // JSON-B omits a null field rather than serialising it as null — the exact shape that made
    // every guest checkout return 503 for months.
    assertThat(TenantClient.parseCurrency("{\"data\":{\"id\":\"x\"}}"), is(Optional.empty()));
    assertThat(TenantClient.parseCurrency("{\"data\":null}"), is(Optional.empty()));
    assertThat(TenantClient.parseCurrency("{}"), is(Optional.empty()));
  }

  @Test
  @DisplayName("A currency that is not three characters is refused rather than stamped onto money")
  void tenantCurrencyMalformed() {
    assertThat(
        TenantClient.parseCurrency("{\"data\":{\"currency\":\"POUNDS\"}}"), is(Optional.empty()));
    assertThat(TenantClient.parseCurrency("{\"data\":{\"currency\":\"\"}}"), is(Optional.empty()));
  }

  @Test
  @DisplayName("A body that is not JSON at all is empty, not a 500")
  void tenantCurrencyGarbage() {
    assertThat(TenantClient.parseCurrency("<html>502 Bad Gateway</html>"), is(Optional.empty()));
    assertThat(TenantClient.parseCurrency(""), is(Optional.empty()));
  }

  // ── VAT rates ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("VAT codes and rates come back as a map")
  void vatRates() {
    String body =
        "{\"data\":[{\"code\":\"T1\",\"rate\":0.20,\"exempt\":false},"
            + "{\"code\":\"T0\",\"rate\":0.00,\"exempt\":false}]}";
    Map<String, BigDecimal> rates = PricingClient.parseRates(body);

    assertThat(rates.size(), is(2));
    assertThat(rates.get("T1"), comparesEqualTo(new BigDecimal("0.20")));
    assertThat(rates.get("T0"), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("An exempt code rates zero even when its rate column says otherwise")
  void exemptBeatsRate() {
    // Nothing in the schema constrains the two against each other, so they can disagree.
    String body = "{\"data\":[{\"code\":\"T5\",\"rate\":0.20,\"exempt\":true}]}";
    assertThat(PricingClient.parseRates(body).get("T5"), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("A tenant with no VAT rates configured yields an empty map, which is not an error")
  void noRatesConfigured() {
    assertThat(PricingClient.parseRates("{\"data\":[]}"), is(anEmptyMap()));
  }

  @Test
  @DisplayName("Codes are upper-cased so a lower-case line matches an upper-case rate")
  void ratesAreUpperCased() {
    String body = "{\"data\":[{\"code\":\"gst18\",\"rate\":0.18}]}";
    assertThat(
        PricingClient.parseRates(body).get("GST18"), comparesEqualTo(new BigDecimal("0.18")));
  }

  @Test
  @DisplayName("A row with no rate at all contributes zero rather than a null in the map")
  void missingRateKey() {
    assertThat(
        PricingClient.parseRates("{\"data\":[{\"code\":\"T1\"}]}").get("T1"),
        comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("Malformed and unexpected payloads are empty, never an exception on the write path")
  void malformedRates() {
    assertThat(PricingClient.parseRates("{\"data\":null}"), is(anEmptyMap()));
    assertThat(PricingClient.parseRates("{\"data\":\"not an array\"}"), is(anEmptyMap()));
    assertThat(
        PricingClient.parseRates("{\"data\":[\"a string\",{\"code\":\"T1\",\"rate\":0.2}]}")
            .get("T1"),
        comparesEqualTo(new BigDecimal("0.2")));
    assertThat(PricingClient.parseRates("nonsense"), is(anEmptyMap()));
  }
}
