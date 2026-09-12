package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.order.domain.Domain.TseStamp;
import java.util.UUID;

/**
 * A German security module (TSE), behind one interface (18.5).
 *
 * <p>The law certifies the module, not the till: what a till must do is hand every transaction to a
 * certified module and print what comes back. {@link SimulatedTseProvider} is a module in software
 * so a stack with no certified device still stamps every document and can be tested; {@link
 * CloudTseProvider} hands the transaction to a provider whose certificate covers the signing.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans, selected per store by {@link
 * TseProviders#forName} on the device's registered provider.
 */
public interface TseProvider {

  /**
   * @return the {@code TseDevice} provider constant this implementation serves
   */
  String name();

  /**
   * @return whether this provider can be used on this deployment — a cloud module needs
   *     credentials, a simulated one needs nothing
   */
  default boolean isConfigured() {
    return true;
  }

  /**
   * Registers a device for a store: a simulated module generates its key; a cloud module is looked
   * up at the provider by the id the manager supplied.
   *
   * @param req who is registering what
   * @return the device, with its id minted, ready to insert
   * @throws TseException when the provider refuses or cannot be reached
   * @throws com.shelfj.web.ApiException when the request lacks what this provider needs
   */
  TseDevice register(RegistrationRequest req);

  /**
   * Signs one sale: opens the transaction, closes it with the process data, returns what the
   * receipt must print.
   *
   * @param device the store's registered device
   * @param sale the figures
   * @return the stamp
   * @throws TseException when the module could not sign — the caller records the outage
   */
  TseStamp sign(TseDevice device, SaleFigures sale);

  /**
   * @param tenantId owning tenant
   * @param storeId the store
   * @param clientId what the device will know this register by
   * @param externalTssId the provider's id for the device, for a cloud module
   * @param registeredBy the manager registering it
   */
  record RegistrationRequest(
      UUID tenantId, UUID storeId, String clientId, String externalTssId, UUID registeredBy) {}

  /** The module could not sign. The sale goes ahead; the failure is stored on the document. */
  class TseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message what went wrong, in the words the document will carry
     * @param cause the underlying failure, or null
     */
    public TseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
