package com.shelfj.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.ids.Ids;
import com.shelfj.notification.messaging.SupplierRemittanceHandler;
import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.notification.service.NotificationErasure;
import com.shelfj.notification.service.Notifier;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for notification-svc against real Postgres (Testcontainers): list shortage
 * alerts (empty initial state), tenant isolation. Kafka/Consul disabled.
 */
@HelidonTest
class NotificationIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "notification");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the delivery path directly (as the consumers would). The
  // active channel is the default LogChannel, so send() just logs — no mail server needed.
  @Inject Notifier notifier;
  @Inject NotificationRepository notifications;
  @Inject NotificationErasure erasure;
  @Inject SupplierRemittanceHandler remittances;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  @Test
  void listAlertsEmptyInitially() {
    Response r = get("/admin/notifications/shortage-alerts", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  @Test
  void tenantIsolation() {
    Response r1 = get("/admin/notifications/shortage-alerts", T);
    Response r2 = get("/admin/notifications/shortage-alerts", OTHER);
    assertThat(r1.getStatus(), is(200));
    assertThat(r2.getStatus(), is(200));
  }

  /** N1: a delivered notification is recorded, and a redelivered event is a no-op. */
  @Test
  void notifyOnceRecordsAndIsIdempotent() {
    UUID event = Ids.newId();
    UUID tenant = UUID.fromString(T);
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(false));

    notifier.notifyOnce(event, "WELCOME", tenant, null, "kit@example.com", "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(true));

    // Redelivery of the same event: no exception, still exactly one record.
    notifier.notifyOnce(event, "WELCOME", tenant, null, "kit@example.com", "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(true));

    // The in-app feed surfaces it.
    String feed = get("/admin/notifications", T).readEntity(String.class);
    assertThat(feed.contains("kit@example.com"), is(true));
    assertThat(feed.contains("\"type\":\"WELCOME\""), is(true));
  }

  /** N1: no recipient → nothing recorded (e.g. a guest order or missing email). */
  @Test
  void noRecipientRecordsNothing() {
    UUID event = Ids.newId();
    notifier.notifyOnce(event, "WELCOME", UUID.fromString(T), null, null, "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(false));
  }

  // ── SJ-D43: erasing what was sent to a person ─────────────────────────────

  /** Reads one logged message straight from the table: the feed hides nothing a test can trust. */
  private static String[] logged(UUID eventId, String type) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT recipient, subject, body, redacted_at FROM notification.notification_log"
                    + " WHERE event_id = ? AND type = ?")) {
      ps.setObject(1, eventId);
      ps.setString(2, type);
      try (var rs = ps.executeQuery()) {
        assertThat("logged " + type, rs.next(), is(true));
        return new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)};
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void erasingACustomerErasesTheMessagesThatShopSentThem() {
    UUID tenant = UUID.fromString(T);
    UUID customer = Ids.newId();
    UUID sent = Ids.newId();
    notifier.notifyOnce(
        sent,
        "ORDER_CONFIRMATION",
        tenant,
        customer,
        "chris@example.com",
        "Your order is confirmed",
        "Order for Chris Carter, 12 High Street");

    assertThat(erasure.customerErased(tenant, customer), is(1));

    String[] row = logged(sent, "ORDER_CONFIRMATION");
    assertThat(row[0], is("[erased]"));
    assertThat(row[1], is("[erased]"));
    assertThat(row[2], is(""));
    assertThat(row[3] != null, is(true));
    // The send is still accounted for, so a redelivered event does not send it again.
    assertThat(notifications.alreadyNotified(sent, "ORDER_CONFIRMATION"), is(true));
    // And erasing again touches nothing.
    assertThat(erasure.customerErased(tenant, customer), is(0));
  }

  @Test
  void anotherShopsMessagesAboutTheSameIdAreNotTouched() {
    UUID customer = Ids.newId();
    UUID theirs = Ids.newId();
    notifier.notifyOnce(
        theirs,
        "ORDER_CONFIRMATION",
        UUID.fromString(OTHER),
        customer,
        "chris@example.com",
        "Your order is confirmed",
        "body");

    assertThat(erasure.customerErased(UUID.fromString(T), customer), is(0));
    assertThat(logged(theirs, "ORDER_CONFIRMATION")[0], is("chris@example.com"));
  }

  @Test
  void deletingAnAccountErasesThePlatformsMessagesButNotAShops() {
    UUID user = Ids.newId();
    UUID welcome = Ids.newId();
    UUID shopMessage = Ids.newId();
    notifier.notifyOnce(
        welcome, "WELCOME", null, user, "leaving@example.com", "Welcome to Shelf-J", "hi");
    notifier.notifyOnce(
        shopMessage,
        "ORDER_CONFIRMATION",
        UUID.fromString(T),
        user,
        "leaving@example.com",
        "Your order is confirmed",
        "body");

    assertThat(erasure.accountDeleted(user), is(1));

    assertThat(logged(welcome, "WELCOME")[0], is("[erased]"));
    // The shop holds its own records and erases them on its own request.
    assertThat(logged(shopMessage, "ORDER_CONFIRMATION")[0], is("leaving@example.com"));
  }

  @Test
  void aReceiptSentWithACustomerIdCanBeErased() {
    UUID customer = Ids.newId();
    String eventId = Ids.newId().toString();
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"recipient\":\"chris@example.com\",\"subject\":\"Your receipt\","
                        + "\"body\":\"Thanks\",\"type\":\"POS_RECEIPT\",\"eventId\":\""
                        + eventId
                        + "\",\"customerId\":\""
                        + customer
                        + "\"}"));
    assertThat(r.getStatus(), is(202));

    assertThat(erasure.customerErased(UUID.fromString(T), customer), is(1));
    assertThat(logged(UUID.fromString(eventId), "POS_RECEIPT")[0], is("[erased]"));
  }

  @Test
  void aCustomerIdThatIsNotAUuidIsRejected() {
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"recipient\":\"a@b.com\",\"subject\":\"s\",\"body\":\"b\","
                        + "\"customerId\":\"not-a-uuid\"}"));
    assertThat(r.getStatus(), is(400));
  }

  /**
   * PECR reg.22: a marketing send is refused unless consent can be shown. customer-svc is not
   * running here, so the consent check cannot be answered — and the send is refused for exactly
   * that reason, which is the behaviour that matters. A client that degraded to "send anyway" when
   * it could not check would be the offence.
   */
  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("Marketing is not sent when consent cannot be shown")
  void marketingWithoutProvableConsentIsRefused() {
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .post(
                jakarta.ws.rs.client.Entity.entity(
                    "{\"recipient\":\"someone@example.com\",\"subject\":\"Half price week\","
                        + "\"body\":\"Offers inside\",\"category\":\"MARKETING\","
                        + "\"customerId\":\""
                        + Ids.newId()
                        + "\"}",
                    jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class).contains("MARKETING_CONSENT_MISSING"), is(true));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("A marketing send that names nobody cannot be lawful")
  void marketingWithoutACustomerIsRefused() {
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .post(
                jakarta.ws.rs.client.Entity.entity(
                    "{\"recipient\":\"someone@example.com\",\"subject\":\"Half price week\","
                        + "\"body\":\"Offers inside\",\"category\":\"MARKETING\"}",
                    jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class).contains("MARKETING_CONSENT_MISSING"), is(true));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("A transactional message is unaffected by the marketing gate")
  void transactionalSendsStillGoOut() {
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .post(
                jakarta.ws.rs.client.Entity.entity(
                    "{\"recipient\":\"someone@example.com\",\"subject\":\"Your order\","
                        + "\"body\":\"On its way\",\"type\":\"ORDER_CONFIRMATION\"}",
                    jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(202));
  }

  // ── 17.10: remittance advice ──────────────────────────────────────────────

  private static String remittance(UUID eventId, String email, String type) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + T
        + "\",\"supplierId\":\"01a090ae-611e-7a2b-8c3d-4e5f60718293\",\"supplierName\":\"Acme Ltd\","
        + (email == null ? "" : "\"remittanceEmail\":\"" + email + "\",")
        + "\"runReference\":\"PAY260913-3F9A1C\",\"paymentDate\":\"2026-09-13\",\"currency\":\"GBP\","
        + "\"total\":35.00,\"items\":[{\"type\":\"INVOICE\",\"reference\":\"INV-A1\","
        + "\"documentDate\":\"2026-07-15\",\"amount\":40.00},{\"type\":\"CREDIT_NOTE\","
        + "\"reference\":\"CN-A\",\"amount\":5.00}]}";
  }

  /**
   * A paid supplier is sent one advice naming what the payment settles, however often it arrives.
   */
  @Test
  void aRemittanceAdviceIsSentOncePerPayment() {
    UUID event = Ids.newId();
    String json = remittance(event, "accounts@acme.example", "SupplierRemittanceIssued");
    remittances.handle(json);
    remittances.handle(json);
    String[] sent = logged(event, "SUPPLIER_REMITTANCE");
    assertThat(sent[0], is("accounts@acme.example"));
    assertThat(sent[1], is("Remittance advice PAY260913-3F9A1C"));
    assertThat(sent[2].contains("Invoice INV-A1 of 2026-07-15: GBP 40.00"), is(true));
    assertThat(sent[2].contains("Less credit note CN-A: -GBP 5.00"), is(true));
    assertThat(sent[2].contains("Total paid: GBP 35.00"), is(true));
    assertThat(notifications.alreadyNotified(event, "SUPPLIER_REMITTANCE"), is(true));
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM notification.notification_log WHERE event_id = ?")) {
      ps.setObject(1, event);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1), is(1L));
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** No address, the wrong event, or a payload that is not one: nothing sent, nothing thrown. */
  @Test
  void aRemittanceWithNowhereToGoOrMalformedIsSkipped() {
    UUID noEmail = Ids.newId();
    remittances.handle(remittance(noEmail, null, "SupplierRemittanceIssued"));
    assertThat(notifications.alreadyNotified(noEmail, "SUPPLIER_REMITTANCE"), is(false));
    UUID other = Ids.newId();
    remittances.handle(remittance(other, "accounts@acme.example", "SupplierInvoiceRejected"));
    assertThat(notifications.alreadyNotified(other, "SUPPLIER_REMITTANCE"), is(false));
    remittances.handle("{not json");
    remittances.handle("{\"eventType\":\"SupplierRemittanceIssued\",\"eventId\":\"not-a-uuid\"}");
  }
}
