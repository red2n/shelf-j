package com.shelfj.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.web.ApiException;
import org.junit.jupiter.api.Test;

/** What a delivery is refused for before its document is read. */
class EInvoiceDeliveryServiceTest {

  private static EInvoiceDeliveryService withKey(String key) {
    EInvoiceDeliveryService s = new EInvoiceDeliveryService();
    s.deliveryKey = java.util.Optional.ofNullable(key);
    return s;
  }

  @Test
  void aDeploymentWithNoKeyTakesNoDeliveriesAndSaysSo() {
    ApiException e = assertThrows(ApiException.class, () -> withKey("").requireKey("anything"));
    assertThat(e.status(), is(503));
    assertThat(e.code(), is("PURCHASE_EINVOICE_DELIVERY_OFF"));
    assertThrows(ApiException.class, () -> withKey(null).requireKey("anything"));
  }

  @Test
  void theKeyMustMatchExactlyAndBePresent() {
    EInvoiceDeliveryService s = withKey("s3cret");
    s.requireKey("s3cret");
    for (String wrong : new String[] {null, "", "S3CRET", "s3cret ", "s3cre", "s3cret2"}) {
      ApiException e = assertThrows(ApiException.class, () -> s.requireKey(wrong));
      assertThat(String.valueOf(wrong), e.status(), is(401));
      assertThat(e.code(), is("PURCHASE_EINVOICE_KEY_REFUSED"));
    }
  }

  @Test
  void onlyTheNetworksThatDeliverInAreChannels() {
    assertThat(EInvoiceDeliveryService.channelOf("peppol"), is("PEPPOL"));
    assertThat(EInvoiceDeliveryService.channelOf(" Fr_PdP "), is("FR_PDP"));
    assertThat(EInvoiceDeliveryService.channelOf("SIMULATED"), is("SIMULATED"));
    for (String not : new String[] {null, "", "KSEF", "IRP", "UPLOAD", "fax", "peppol/x"}) {
      ApiException e =
          assertThrows(ApiException.class, () -> EInvoiceDeliveryService.channelOf(not));
      assertThat(String.valueOf(not), e.code(), is("PURCHASE_EINVOICE_NETWORK_UNKNOWN"));
    }
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> EInvoiceDeliveryService.channelOf("<script>" + "x".repeat(60)));
    assertThat("echoed only as it may be", e.getMessage(), containsString("'?script?xxx"));
    assertThat(e.getMessage().contains("<"), is(false));
  }

  @Test
  void aReferenceIsKeptTrimmedOrNotAtAllAndNeverOverlong() {
    assertThat(EInvoiceDeliveryService.referenceOf(null), nullValue());
    assertThat(EInvoiceDeliveryService.referenceOf("  "), nullValue());
    assertThat(EInvoiceDeliveryService.referenceOf(" AP-1 "), is("AP-1"));
    assertThat(EInvoiceDeliveryService.referenceOf("x".repeat(200)), is("x".repeat(200)));
    ApiException e =
        assertThrows(
            ApiException.class, () -> EInvoiceDeliveryService.referenceOf("x".repeat(201)));
    assertThat(e.code(), is("PURCHASE_EINVOICE_REFERENCE_TOO_LONG"));
  }
}
