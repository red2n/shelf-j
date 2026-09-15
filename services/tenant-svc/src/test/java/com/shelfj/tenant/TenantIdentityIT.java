package com.shelfj.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The business's e-invoicing identity (07.13, 18.9): the VAT identifier and electronic address its
 * e-invoices name it by — set by the owner, checked where it is typed, changed only when asked, and
 * no other business's.
 */
@HelidonTest
class TenantIdentityIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String onboard(String name) {
    return onboard(name, "GB", "GBP");
  }

  private String onboard(String name, String country, String currency) {
    return TenantOnboarding.onboard(target, name, country, currency);
  }

  private Response put(String tenant, String role, String json) {
    return target
        .path("/admin/tenant")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", role)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String tenant) {
    Response r =
        target
            .path("/admin/tenant")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  @Test
  void theOwnerSetsTheIdentityAndLaterChangesLeaveWhatWasNotSent() {
    String tenant = onboard("Identity");
    body(
        put(
            tenant,
            "OWNER",
            "{\"businessName\":\"Corner Shop\",\"vatNumber\":\"gb 123 456 789\","
                + "\"einvoiceScheme\":\"0088\",\"einvoiceId\":\"5790000435975\"}"),
        200);
    String read = get(tenant);
    assertThat(read, read, containsString("\"vatNumber\":\"GB123456789\""));
    assertThat(read, read, containsString("\"einvoiceScheme\":\"0088\""));
    assertThat(read, read, containsString("\"einvoiceId\":\"5790000435975\""));

    body(put(tenant, "OWNER", "{\"businessName\":\"Corner Shop Ltd\"}"), 200);
    read = get(tenant);
    assertThat(
        "a rename keeps the identity", read, containsString("\"vatNumber\":\"GB123456789\""));
    assertThat(read, read, containsString("\"einvoiceId\":\"5790000435975\""));

    body(
        put(
            tenant,
            "OWNER",
            "{\"businessName\":\"Corner Shop Ltd\",\"vatNumber\":\"\",\"einvoiceScheme\":\"\",\"einvoiceId\":\"\"}"),
        200);
    read = get(tenant);
    assertThat("empty removes it", read, not(containsString("GB123456789")));
    assertThat(read, read, not(containsString("5790000435975")));
  }

  @Test
  void whatCannotBeAnIdentityIsRefusedWhereItIsTyped() {
    String tenant = onboard("Refusals");
    assertThat(
        body(put(tenant, "OWNER", "{\"businessName\":\"X\",\"vatNumber\":\"123456789\"}"), 400),
        containsString("TENANT_VAT_NUMBER_INVALID"));
    assertThat(
        body(put(tenant, "OWNER", "{\"businessName\":\"X\",\"vatNumber\":\"QQ123456789\"}"), 400),
        containsString("TENANT_VAT_NUMBER_INVALID"));
    assertThat(
        body(
            put(
                tenant,
                "OWNER",
                "{\"businessName\":\"X\",\"einvoiceScheme\":\"9999\",\"einvoiceId\":\"1\"}"),
            400),
        containsString("TENANT_EINVOICE_ADDRESS_INVALID"));
    assertThat(
        body(put(tenant, "OWNER", "{\"businessName\":\"X\",\"einvoiceScheme\":\"0088\"}"), 400),
        containsString("TENANT_EINVOICE_ADDRESS_INVALID"));
    assertThat(
        body(
            put(
                tenant,
                "OWNER",
                "{\"businessName\":\"X\",\"einvoiceScheme\":\"0088\",\"einvoiceId\":\"5790000435976\"}"),
            400),
        containsString("check digits"));
    assertThat(
        body(
            put(
                tenant,
                "OWNER",
                "{\"businessName\":\"X\",\"einvoiceScheme\":\"0088\",\"einvoiceId\":\""
                    + "9".repeat(129)
                    + "\"}"),
            400),
        containsString("einvoiceId"));
    String read = get(tenant);
    assertThat("nothing refused was kept", read, not(containsString("\"vatNumber\":\"")));
  }

  @Test
  void anIndianBusinessIsNamedByItsGstinAndOnlyThere() {
    String india = onboard("Mumbai Traders", "IN", "INR");
    body(
        put(
            india,
            "OWNER",
            "{\"businessName\":\"Mumbai Traders\",\"vatNumber\":\"27aapfu0939f1zv\"}"),
        200);
    assertThat(get(india), containsString("\"vatNumber\":\"27AAPFU0939F1ZV\""));
    assertThat(
        "a GSTIN whose check character is wrong",
        body(
            put(india, "OWNER", "{\"businessName\":\"X\",\"vatNumber\":\"27AAPFU0939F1ZW\"}"), 400),
        containsString("TENANT_VAT_NUMBER_INVALID"));
    assertThat(
        "a European VAT identifier is not an Indian business's",
        body(put(india, "OWNER", "{\"businessName\":\"X\",\"vatNumber\":\"GB123456789\"}"), 400),
        containsString("TENANT_VAT_NUMBER_INVALID"));
    assertThat(get(india), containsString("27AAPFU0939F1ZV"));
    String uk = onboard("Not India");
    assertThat(
        "nor is a GSTIN a British business's",
        body(put(uk, "OWNER", "{\"businessName\":\"X\",\"vatNumber\":\"27AAPFU0939F1ZV\"}"), 400),
        containsString("TENANT_VAT_NUMBER_INVALID"));
  }

  @Test
  void oneBusinessesIdentityIsNotAnothersAndACashierCannotSetIt() {
    String mine = onboard("Mine");
    String theirs = onboard("Theirs");
    body(put(mine, "OWNER", "{\"businessName\":\"Mine\",\"vatNumber\":\"GB123456789\"}"), 200);
    assertThat(get(theirs), not(containsString("GB123456789")));
    Response cashier =
        put(mine, "CASHIER", "{\"businessName\":\"Mine\",\"vatNumber\":\"GB987654321\"}");
    assertThat(cashier.readEntity(String.class), cashier.getStatus(), anyOf(is(401), is(403)));
    assertThat(get(mine), containsString("GB123456789"));
  }
}
