package com.storeql.customer;

import com.storeql.test.PermissionGate;
import com.storeql.test.PostgresSupport;
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

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

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
  @DisplayName("Exporting or erasing a customer needs customers.privacy")
  void privacyActionsAreGated() {
    gate().assertGated("GET", "/customers/" + ID + "/export", null, "customers.privacy");
    gate().assertGated("DELETE", "/customers/" + ID, null, "customers.privacy");
  }
}
