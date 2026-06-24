package com.shelfj.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.tenant.dto.Dtos.UpsertInventoryConfigRequest;
import com.shelfj.web.ApiException;
import com.shelfj.web.ErrorCodes;
import com.shelfj.web.Validations;
import org.junit.jupiter.api.Test;

/** A4: the previously-unvalidated costing method is now constrained at the boundary. */
class InventoryConfigValidationTest {

  @Test
  void rejectsUnknownCostingMethod() {
    var req = new UpsertInventoryConfigRequest(null, null, null, null, "BANANA", null, null, null);
    ApiException ex = assertThrows(ApiException.class, () -> Validations.validate(req));
    assertEquals(400, ex.status());
    assertEquals(ErrorCodes.VALIDATION_FAILED, ex.code());
  }

  @Test
  void acceptsSupportedCostingMethodAndNulls() {
    assertDoesNotThrow(
        () ->
            Validations.validate(
                new UpsertInventoryConfigRequest(
                    true, null, null, null, "AVERAGE", "EA", null, null)));
    // An all-null config (every field optional) is valid.
    assertDoesNotThrow(
        () ->
            Validations.validate(
                new UpsertInventoryConfigRequest(null, null, null, null, null, null, null, null)));
  }
}
