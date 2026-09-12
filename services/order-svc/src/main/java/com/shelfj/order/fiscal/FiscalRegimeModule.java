package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalStoreSettings;
import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.order.domain.Domain.TseStamp;
import com.shelfj.order.repo.FiscalReceiptRepository.DocumentSigner;

/**
 * One fiscal regime, behind one interface (18.5).
 *
 * <p>Everything the receipt path knows about a country's fiscal law lives in an implementation of
 * this. {@code FiscalService} never names Germany or Portugal: it asks the store's module for the
 * device stamp before the number is taken and for the document signer during allocation, and stores
 * whatever comes back on the document. Adding a market is adding a class and a regime code.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans, selected by {@link
 * FiscalModules#forRegime} on the store's configured regime.
 */
public interface FiscalRegimeModule {

  /**
   * @return the {@code FiscalStoreSettings} regime constant this module serves
   */
  String regime();

  /**
   * Refuses settings the regime cannot trade under — a missing tax number, no device, no signing
   * key — before they are stored, so a store is never under a regime it cannot honour.
   *
   * @param settings what a manager asked for
   * @param device the store's registered security module, or null
   * @throws com.shelfj.web.ApiException with a stable code naming what is missing
   */
  void validate(FiscalStoreSettings settings, TseDevice device);

  /**
   * Asks the regime's device to sign a sale, before its number is taken. A regime with no device
   * returns null. A regime whose device fails returns a stamp that records the failure rather than
   * throwing: the sale goes ahead and the outage is the record, which is what the law asks.
   *
   * @param sale the figures to sign
   * @param device the store's registered device, or null
   * @return the stamp, or null
   */
  TseStamp deviceStamp(SaleFigures sale, TseDevice device);

  /**
   * The signer applied inside allocation, once the number and the previous document are fixed. A
   * regime that signs nothing at that point returns null.
   *
   * @param settings the store's settings
   * @return the signer, or null
   */
  DocumentSigner documentSigner(FiscalStoreSettings settings);
}
