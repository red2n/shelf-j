package com.shelfj.order.einvoice;

import com.shelfj.service.SealedSecrets;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * This service's sealer for a business's provider credentials.
 *
 * <p>The implementation moved to {@link SealedSecrets} in common-service when purchase-svc came to
 * need one too: Poland hands invoices out rather than delivering them, so the inbound side holds a
 * KSeF token of its own. One implementation means a key rotated in one place is not a secret
 * unreadable in the other. The name stays because everything in this service already calls it that.
 */
@ApplicationScoped
public class Secrets extends SealedSecrets {

  /** For tests: a sealer with the key given, or none. */
  public static Secrets forTest(String keyBase64) {
    Secrets s = new Secrets();
    s.useKey(keyBase64);
    return s;
  }
}
