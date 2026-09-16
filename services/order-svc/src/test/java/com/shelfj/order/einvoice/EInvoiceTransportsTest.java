package com.shelfj.order.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.EInvoiceTransports;
import com.shelfj.order.einvoice.EInvoiceTransport.Dispatch;
import com.shelfj.order.einvoice.EInvoiceTransport.Outbound;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The retry schedule, and the simulated network's three answers. */
class EInvoiceTransportsTest {

  @Test
  void aFailedAttemptWaitsTwiceAsLongAsTheLastNeverMoreThanAnHourAndGivesUpAtTheEnd() {
    Duration base = Duration.ofSeconds(30);
    assertEquals(Optional.of(Duration.ofSeconds(30)), EInvoiceTransports.backoff(1, base, 8));
    assertEquals(Optional.of(Duration.ofSeconds(60)), EInvoiceTransports.backoff(2, base, 8));
    assertEquals(Optional.of(Duration.ofSeconds(120)), EInvoiceTransports.backoff(3, base, 8));
    assertEquals(Optional.of(Duration.ofSeconds(1920)), EInvoiceTransports.backoff(7, base, 8));
    assertEquals(Optional.of(Duration.ofHours(1)), EInvoiceTransports.backoff(10, base, 20));
    assertEquals(Optional.empty(), EInvoiceTransports.backoff(8, base, 8));
    assertEquals(Optional.empty(), EInvoiceTransports.backoff(9, base, 8));
  }

  private static Outbound to(String receiver) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        "Invoice",
        "INV/2026/000001",
        "0088:5790000435975",
        receiver,
        "<Invoice/>",
        null,
        null);
  }

  @Test
  void theSimulatedNetworkDeliversRefusesWhatSaysRejectAndDefersWhatSaysLater() {
    SimulatedTransport sim = new SimulatedTransport();
    assertEquals(Set.copyOf(EInvoiceTransports.NETWORKS), sim.networks());
    assertEquals("SIMULATED", sim.name());
    assertTrue(sim.isConfigured());

    Dispatch delivered = sim.send(to("9932:GB555555555"));
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, delivered.outcome().state());
    assertTrue(delivered.providerRef().startsWith("SIM-"));
    Dispatch refused = sim.send(to("9932:GB555555555reject"));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, refused.outcome().state());
    assertTrue(refused.outcome().detail().contains("knows no participant"));
    Dispatch deferred = sim.send(to("9932:LATER1"));
    assertEquals(EInvoiceTransports.STATUS_PENDING, deferred.outcome().state());
    assertEquals(
        EInvoiceTransports.STATUS_ACCEPTED,
        sim.status(to("9932:LATER1"), deferred.providerRef()).state());
    // A network that takes the document from the sender alone: nothing to address.
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, sim.send(to(null)).outcome().state());
  }
}
