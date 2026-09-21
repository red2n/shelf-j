package com.storeql.order.fiscal;

import com.storeql.order.domain.Domain.FiscalStoreSettings;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.domain.Domain.TseStamp;
import com.storeql.order.repo.FiscalReceiptRepository.DocumentSigner;
import jakarta.enterprise.context.ApplicationScoped;

/** The register alone: numbered, hash-chained, unstamped. What every store starts under. */
@ApplicationScoped
public class NoneModule implements FiscalRegimeModule {

  @Override
  public String regime() {
    return FiscalStoreSettings.REGIME_NONE;
  }

  @Override
  public void validate(FiscalStoreSettings settings, TseDevice device) {
    // Nothing is required to keep a register.
  }

  @Override
  public TseStamp deviceStamp(SaleFigures sale, TseDevice device) {
    return null;
  }

  @Override
  public DocumentSigner documentSigner(FiscalStoreSettings settings) {
    return null;
  }
}
