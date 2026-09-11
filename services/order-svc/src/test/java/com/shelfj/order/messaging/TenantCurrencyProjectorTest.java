package com.shelfj.order.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shelfj.ids.Ids;
import com.shelfj.service.TenantStatusRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Two events carry the tenant's currency and are projected by one shared routine. What matters here
 * is that they are deduped under different consumer identities: the projection exists to be
 * repairable, and a repair that the dedupe table swallows as already-seen would be silent and
 * indistinguishable from success.
 */
@ExtendWith(MockitoExtension.class)
class TenantCurrencyProjectorTest {

  private static final UUID TENANT = Ids.newId();

  @Mock TenantStatusRepository tenantStatus;

  private TenantCreatedHandler created;
  private TenantCurrencyDeclaredHandler declared;

  @BeforeEach
  void setUp() {
    TenantCurrencyProjector projector = new TenantCurrencyProjector();
    projector.tenantStatus = tenantStatus;
    created = new TenantCreatedHandler();
    created.projector = projector;
    declared = new TenantCurrencyDeclaredHandler();
    declared.projector = projector;
  }

  private static String payload(String eventType, UUID eventId, String currency) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + eventType
        + "\",\"tenantId\":\""
        + TENANT
        + "\""
        + (currency == null ? "" : ",\"currency\":\"" + currency + "\"")
        + "}";
  }

  @Test
  void aDeclaredCurrencyIsProjected() {
    UUID event = Ids.newId();
    declared.handle(payload("TenantCurrencyDeclared", event, "GBP"));
    verify(tenantStatus)
        .projectTenantCurrencyOnce(
            eq(event), eq(TenantCurrencyDeclaredHandler.CONSUMER_NAME), eq(TENANT), eq("GBP"));
  }

  /**
   * The whole point of the replay path. Dedupe is on (eventId, consumer), so if both events were
   * recorded under one identity a re-announcement of an already-onboarded tenant could be dropped
   * as a redelivery — leaving the projection exactly as broken as before, with a 200 to say it
   * worked.
   */
  @Test
  void theTwoEventsDedupeUnderDifferentConsumerIdentities() {
    UUID event = Ids.newId();
    created.handle(payload("TenantCreated", event, "GBP"));
    verify(tenantStatus)
        .projectTenantCurrencyOnce(
            eq(event), eq(TenantCreatedHandler.CONSUMER_NAME), eq(TENANT), eq("GBP"));

    org.junit.jupiter.api.Assertions.assertNotEquals(
        TenantCreatedHandler.CONSUMER_NAME, TenantCurrencyDeclaredHandler.CONSUMER_NAME);
  }

  /** Leading/trailing space in the payload must not reach the column. */
  @Test
  void theCurrencyIsTrimmedBeforeItIsStored() {
    UUID event = Ids.newId();
    declared.handle(payload("TenantCurrencyDeclared", event, " EUR "));
    verify(tenantStatus).projectTenantCurrencyOnce(eq(event), any(), eq(TENANT), eq("EUR"));
  }

  /**
   * A payload that will never parse is dropped rather than retried forever, and — the part that
   * matters for money — nothing is written. Projecting a guess would stamp the wrong currency onto
   * every order the tenant places; the configured default is the safer fallback.
   */
  @Test
  void aMalformedPayloadProjectsNothing() {
    declared.handle("not json at all");
    declared.handle("{\"eventId\":\"not-a-uuid\",\"tenantId\":\"" + TENANT + "\"}");
    verify(tenantStatus, never()).projectTenantCurrencyOnce(any(), any(), any(), any());
  }

  /** Anything that is not a 3-letter code means a producer contract change, not a currency. */
  @Test
  void aCurrencyThatIsNotAnIso4217CodeProjectsNothing() {
    declared.handle(payload("TenantCurrencyDeclared", Ids.newId(), null));
    declared.handle(payload("TenantCurrencyDeclared", Ids.newId(), "POUNDS"));
    declared.handle(payload("TenantCurrencyDeclared", Ids.newId(), "GB"));
    verify(tenantStatus, never()).projectTenantCurrencyOnce(any(), any(), any(), any());
  }
}
