package com.storeql.purchase;

import com.storeql.test.PermissionGate;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Granular permissions (20.10) at this service's decision points, driven by {@link PermissionGate}:
 * the tier gate by path still admits a MANAGER; the permission check then refuses one whose custom
 * role was narrowed out of the decision, with the permission named, before any argument is looked
 * at; a token carrying no claim is judged by the tier's defaults as before.
 */
@HelidonTest
class PermissionsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(PermissionsIT.T, "GBP", "GB");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String ID = "01a090ae-611e-703c-a378-a4972ea461c8";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private PermissionGate gate() {
    return new PermissionGate(target, T, USER);
  }

  @Test
  @DisplayName("Approving an order, deciding an invoice and posting a journal are each gated")
  void purchasingDecisionsAreGated() {
    gate().assertGated("POST", "/purchase-orders/" + ID + "/approve", "{}", "purchasing.approve");
    gate()
        .assertGated(
            "POST",
            "/supplier-invoices/" + ID + "/resolve",
            "{\"action\":\"APPROVE\",\"reason\":\"fine\"}",
            "purchasing.invoices.decide");
    gate()
        .assertGated(
            "POST",
            "/nominal-ledger/journals",
            "{\"entryDate\":\"2026-02-05\",\"description\":\"x\",\"lines\":[{\"nominalCode\":\"1001\",\"debit\":1},{\"nominalCode\":\"3000\",\"credit\":1}]}",
            "finance.journal");
  }

  @Test
  @DisplayName(
      "Payment runs, the bank file and a supplier's bank details are gated by finance.payments")
  void supplierPaymentsAreGated() {
    gate()
        .assertGated(
            "POST",
            "/payment-runs",
            "{\"payUpTo\":\"2026-01-01\",\"paymentDate\":\"2099-01-01\"}",
            "finance.payments");
    gate().assertGated("POST", "/payment-runs/" + ID + "/approve", "{}", "finance.payments");
    gate().assertGated("POST", "/payment-runs/" + ID + "/pay", "{}", "finance.payments");
    gate().assertGated("GET", "/payment-runs/" + ID + "/bank-file", null, "finance.payments");
    gate()
        .assertGated(
            "POST",
            "/suppliers",
            "{\"name\":\"Gated Ltd\",\"bankAccountName\":\"Gated Ltd\",\"bankSortCode\":\"123456\",\"bankAccountNumber\":\"31415926\"}",
            "finance.payments");
  }
}
