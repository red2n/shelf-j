package com.storeql.order.einvoice;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.order.client.EInvoiceDeliveryClient;
import com.storeql.order.einvoice.EInvoiceTransport.Dispatch;
import com.storeql.order.einvoice.EInvoiceTransport.Outbound;
import com.storeql.order.einvoice.EInvoiceTransport.TransportException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The simulated network delivering into the platform's own inbox. */
class SimulatedTransportTest {

  /** An inbox that answers as told and remembers what it was handed. */
  private static final class Inbox extends EInvoiceDeliveryClient {
    final boolean configured;
    final Delivery answer;
    final List<String> deliveries = new ArrayList<>();

    Inbox(boolean configured, int status, String body) {
      this.configured = configured;
      this.answer = new Delivery(status, body);
    }

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public Delivery deliver(String network, String reference, String ubl) {
      deliveries.add(network + " " + reference + " " + ubl);
      return answer;
    }
  }

  private static SimulatedTransport with(Inbox inbox) {
    SimulatedTransport t = new SimulatedTransport();
    t.inbox = inbox;
    return t;
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
        "GB123456789",
        null,
        null);
  }

  @Test
  void withNoDeliveryKeyTheNetworkOnlyPretends() {
    Inbox inbox = new Inbox(false, 201, "{}");
    Dispatch d = with(inbox).send(to("9932:GB555555555"));
    assertThat(d.outcome().state(), is("ACCEPTED"));
    assertThat(d.outcome().detail(), is("delivered"));
    assertThat("nothing was handed to the inbox", inbox.deliveries.isEmpty(), is(true));
    assertThat(d.providerRef(), containsString("SIM-"));
  }

  @Test
  void aReceiverOnThePlatformGetsTheDocumentInItsInbox() {
    Inbox inbox = new Inbox(true, 201, "{\"data\":{\"id\":\"doc-1\",\"alreadyReceived\":false}}");
    Dispatch d = with(inbox).send(to("9932:GB555555555"));
    assertThat(d.outcome().state(), is("ACCEPTED"));
    assertThat(d.outcome().detail(), containsString("inbox"));
    assertThat(d.outcome().response(), containsString("\"inbox\":{\"data\":{\"id\":\"doc-1\""));
    assertThat(inbox.deliveries.get(0), is("SIMULATED " + d.providerRef() + " <Invoice/>"));
  }

  @Test
  void aReceiverNobodyHereHoldsIsDeliveredTheSimulatedWay() {
    Inbox inbox =
        new Inbox(
            true,
            404,
            "{\"error\":{\"code\":\"PURCHASE_EINVOICE_RECEIVER_UNKNOWN\",\"message\":\"nobody\"}}");
    Dispatch d = with(inbox).send(to("9932:GB555555555"));
    assertThat(d.outcome().state(), is("ACCEPTED"));
    assertThat(d.outcome().detail(), containsString("no business on this platform holds"));
  }

  @Test
  void anInboxThatCannotBeReachedOrRefusesTheKeyIsTriedAgainLater() {
    assertThrows(
        TransportException.class,
        () -> with(new Inbox(true, 0, null)).send(to("9932:GB555555555")));
    TransportException e =
        assertThrows(
            TransportException.class,
            () -> with(new Inbox(true, 401, "{}")).send(to("9932:GB555555555")));
    assertThat(e.getMessage(), containsString("delivery key differs"));
    assertThrows(
        TransportException.class,
        () -> with(new Inbox(true, 503, "{}")).send(to("9932:GB555555555")));
  }

  @Test
  void anInboxThatRefusesTheDocumentIsARefusal() {
    Inbox inbox =
        new Inbox(
            true,
            422,
            "{\"error\":{\"code\":\"PURCHASE_EINVOICE_RECEIVER_UNNAMED\",\"message\":\"names no"
                + " buyer\"}}");
    Dispatch d = with(inbox).send(to("9932:GB555555555"));
    assertThat(d.outcome().state(), is("REJECTED"));
    assertThat(d.outcome().detail(), containsString("refused the document: names no buyer"));
  }

  @Test
  void theTestReceiversStillNeverReachTheInbox() {
    Inbox inbox = new Inbox(true, 201, "{}");
    assertThat(with(inbox).send(to("9932:GB1REJECT")).outcome().state(), is("REJECTED"));
    assertThat(with(inbox).send(to("9932:GB1LATER")).outcome().state(), is("PENDING"));
    assertThat(inbox.deliveries.isEmpty(), is(true));
  }

  @Test
  void aCheckIsReadyAndSaysExactlyWhatReadyMeansOnAPlatformWithNoContract() {
    Inbox inbox = new Inbox(true, 201, "{\"data\":{\"id\":\"1\"}}");
    EInvoiceTransport.Readiness r =
        with(inbox)
            .check(
                new Outbound(
                    Ids.newId(),
                    Ids.newId(),
                    "Check",
                    "readiness",
                    "0088:1",
                    null,
                    "",
                    null,
                    "GB123456789",
                    null,
                    null));
    assertThat(r.state(), is(EInvoiceTransport.Readiness.READY));
    assertThat(r.detail(), containsString("nothing leaves it"));
    assertThat(r.detail(), containsString("a provider contract"));
    assertThat("a check delivers nothing", inbox.deliveries, is(List.of()));
  }
}
