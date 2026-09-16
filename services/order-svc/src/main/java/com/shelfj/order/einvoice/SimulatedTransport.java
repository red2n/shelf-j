package com.shelfj.order.einvoice;

import com.shelfj.order.domain.EInvoiceTransports;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Set;

/**
 * The platform standing in for every network: what a stack with no provider contract sends on, and
 * exactly why it is not a network — nothing leaves the platform. Every document is taken and
 * delivered at once, except two the tests and the demonstrations rely on: a receiver whose
 * identifier contains {@code REJECT} is refused as unknown to the network, and one containing
 * {@code LATER} is taken but answered only when asked after, the way an access point answers.
 */
@ApplicationScoped
public class SimulatedTransport implements EInvoiceTransport {

  static final String REFUSED = "REJECT";
  static final String DEFERRED = "LATER";

  @Override
  public Set<String> networks() {
    return Set.copyOf(EInvoiceTransports.NETWORKS);
  }

  @Override
  public String name() {
    return EInvoiceTransports.PROVIDER_SIMULATED;
  }

  @Override
  public String configuration() {
    return "nothing: the platform stands in for the network, and nothing leaves it";
  }

  @Override
  public Dispatch send(Outbound d) {
    String ref =
        "SIM-"
            + d.invoiceId().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    String receiver = d.receiver() == null ? "" : d.receiver().toUpperCase(Locale.ROOT);
    if (receiver.contains(REFUSED)) {
      return new Dispatch(
          ref,
          Outcome.rejected(
              "the network knows no participant " + d.receiver(),
              json(ref, "REJECTED", "unknown participant")));
    }
    if (receiver.contains(DEFERRED)) {
      return new Dispatch(
          ref,
          Outcome.pending(
              "taken; the receiver's access point has not answered yet",
              json(ref, "PENDING", "awaiting the receiver")));
    }
    return new Dispatch(ref, Outcome.accepted("delivered", json(ref, "DELIVERED", "delivered")));
  }

  @Override
  public Outcome status(Outbound d, String providerRef) {
    return Outcome.accepted("delivered", json(providerRef, "DELIVERED", "delivered"));
  }

  private static String json(String ref, String state, String message) {
    return "{\"provider\":\"SIMULATED\",\"reference\":\""
        + ref
        + "\",\"state\":\""
        + state
        + "\",\"message\":\""
        + message
        + "\"}";
  }
}
