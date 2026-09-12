package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalStoreSettings;
import com.shelfj.order.domain.Domain.PtStamp;
import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.order.domain.Domain.TseStamp;
import com.shelfj.order.repo.FiscalReceiptRepository.DocumentSigner;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;

/**
 * Portugal: certified invoicing software (Decreto-Lei 198/2012, Portaria 363/2010, Despacho
 * 8632/2014) and SAF-T (PT) (Portaria 302/2016). Every document carries an RSA-SHA1 signature over
 * its own figures chained to the previous document's, computed under the same lock as its number,
 * and the register exports as the SAF-T audit file.
 *
 * <p>The signing key is configuration. The AT certifies the software against its public half and
 * issues the certificate number every document prints; both are values a deployment sets, not code.
 */
@ApplicationScoped
public class SaftPtModule implements FiscalRegimeModule {

  @Inject PtSigningKey key;

  @Override
  public String regime() {
    return FiscalStoreSettings.REGIME_PT_SAFT;
  }

  @Override
  public void validate(FiscalStoreSettings settings, TseDevice device) {
    if (!PtSignature.isValidNif(settings.taxRegistrationNumber())) {
      throw ApiException.badRequest(
          "FISCAL_NIF_INVALID", "A Portuguese store needs a valid nine-digit NIF");
    }
    if (!key.isConfigured()) {
      throw ApiException.conflict(
          "FISCAL_PT_KEY_NOT_CONFIGURED",
          "No Portuguese signing key is configured (shelfj.fiscal.pt.private-key); the software"
              + " cannot sign documents until the producer's key is installed");
    }
    if (settings.seriesValidationCode() != null
        && !settings.seriesValidationCode().isBlank()
        && !settings.seriesValidationCode().matches("[A-Za-z0-9]{1,16}")) {
      throw ApiException.badRequest(
          "FISCAL_SERIES_CODE_INVALID",
          "The AT series validation code is letters and digits, at most 16");
    }
  }

  @Override
  public TseStamp deviceStamp(SaleFigures sale, TseDevice device) {
    return null;
  }

  @Override
  public DocumentSigner documentSigner(FiscalStoreSettings settings) {
    String certificate = settings.certificateNumber();
    String validation =
        settings.seriesValidationCode() == null || settings.seriesValidationCode().isBlank()
            ? "0"
            : settings.seriesValidationCode();
    return (numbered, previous) -> {
      String invoiceNo = PtSignature.invoiceNo(numbered.fullNumber(), numbered.number());
      String previousHash = previous == null || previous.pt() == null ? "" : previous.pt().hash();
      String canonical =
          PtSignature.canonical(
              numbered.issuedAt().atZone(ZoneOffset.UTC).toLocalDate(),
              numbered.issuedAt(),
              invoiceNo,
              numbered.grossTotal(),
              previousHash);
      String hash = PtSignature.sign(key.privateKey(), canonical);
      return new PtStamp(
          invoiceNo,
          hash,
          key.keyVersion(),
          validation + "-" + numbered.number(),
          certificate,
          PtSignature.excerpt(hash));
    };
  }
}
