package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalStoreSettings;
import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.order.domain.Domain.TseStamp;
import com.shelfj.order.repo.FiscalReceiptRepository.DocumentSigner;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Germany: §146a AO and the KassenSichV. Every sale is a transaction a certified security module
 * signs, the receipt prints what the module returned, and the register exports as DSFinV-K.
 *
 * <p>When the module cannot be reached the sale still goes ahead — the law does not let a till
 * refuse a customer because the module is down — and the document records the outage in place of
 * the signature, which is what DSFinV-K's {@code TSE_TA_FEHLER} is for.
 */
@ApplicationScoped
public class KassenSichVModule implements FiscalRegimeModule {

  private static final System.Logger LOG = System.getLogger(KassenSichVModule.class.getName());

  @Inject TseProviders providers;

  @Override
  public String regime() {
    return FiscalStoreSettings.REGIME_DE_KASSENSICHV;
  }

  @Override
  public void validate(FiscalStoreSettings settings, TseDevice device) {
    if (settings.taxRegistrationNumber() == null || settings.taxRegistrationNumber().isBlank()) {
      throw ApiException.badRequest(
          "FISCAL_TAX_NUMBER_REQUIRED",
          "A German store needs its Steuernummer or USt-IdNr on the register");
    }
    if (device == null) {
      throw ApiException.badRequest(
          "FISCAL_TSE_REQUIRED", "A German store signs with a security module; register one");
    }
    if (providers.forName(device.provider()) == null) {
      throw ApiException.conflict(
          "FISCAL_TSE_PROVIDER_UNKNOWN",
          "The store's device names provider " + device.provider() + ", which is not deployed");
    }
  }

  @Override
  public TseStamp deviceStamp(SaleFigures sale, TseDevice device) {
    String clientId = device == null ? null : device.clientId();
    if (device == null) {
      return TseStamp.failed(clientId, "no security module registered for this store");
    }
    TseProvider provider = providers.forName(device.provider());
    if (provider == null) {
      return TseStamp.failed(clientId, "provider " + device.provider() + " is not deployed");
    }
    try {
      return provider.sign(device, sale);
    } catch (TseProvider.TseException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "TSE could not sign sale {0}: {1}",
          sale.orderId(),
          e.getMessage());
      return TseStamp.failed(clientId, e.getMessage());
    }
  }

  @Override
  public DocumentSigner documentSigner(FiscalStoreSettings settings) {
    return null;
  }
}
