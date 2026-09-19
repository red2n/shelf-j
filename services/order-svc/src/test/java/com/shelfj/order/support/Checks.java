package com.shelfj.order.support;

import com.shelfj.ids.Ids;
import com.shelfj.order.einvoice.EInvoiceTransport.Outbound;

/**
 * What the transport service hands a readiness check: the credentials a business holds, and nothing
 * to send.
 *
 * <p>Here rather than in each provider's test because every provider is asked the same way, and a
 * check's outbound is the one shape that must stay empty — a fixture that quietly grew a document
 * would let a check send one.
 */
public final class Checks {

  private Checks() {}

  public static Outbound credentials(String sender, String vatId, String account, String secret) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        "Check",
        "readiness",
        sender,
        null,
        "",
        null,
        vatId,
        account,
        secret);
  }
}
