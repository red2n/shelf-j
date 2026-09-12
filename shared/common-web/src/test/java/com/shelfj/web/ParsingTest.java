package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The point of these parsers is that one class of caller mistake gets one machine code, whichever
 * endpoint it was made against — so the assertions here are mostly on {@link ErrorCodes} values
 * rather than on the parsing itself, which is the JDK's job.
 */
class ParsingTest {

  private static final String VALID_UUID = "01a090ae-611e-702c-a97b-d1b8025478e1";

  @Test
  void requiredParsersRejectMalformedInputWithTheSharedCode() {
    ApiException uuidEx = assertThrows(ApiException.class, () -> Parsing.uuid("nope", "storeId"));
    assertEquals(400, uuidEx.status());
    assertEquals(ErrorCodes.INVALID_UUID, uuidEx.code());

    ApiException dateEx = assertThrows(ApiException.class, () -> Parsing.date("nope", "from"));
    assertEquals(ErrorCodes.INVALID_DATE, dateEx.code());

    ApiException instantEx =
        assertThrows(ApiException.class, () -> Parsing.instant("2026-01-01", "effectiveFrom"));
    assertEquals(ErrorCodes.INVALID_DATE, instantEx.code());
  }

  /** The field name is the whole reason these exist rather than a bare mapper. */
  @Test
  void theMessageNamesTheOffendingField() {
    ApiException ex =
        assertThrows(ApiException.class, () -> Parsing.date("nope", "expectedDelivery"));
    assertTrue(
        ex.getMessage().startsWith("expectedDelivery "),
        "message should lead with the field name, was: " + ex.getMessage());
  }

  /** A parser that loses the cause makes the original parse failure unloggable. */
  @Test
  void theUnderlyingParseFailureIsKeptAsTheCause() {
    ApiException ex = assertThrows(ApiException.class, () -> Parsing.instant("nope", "to"));
    assertTrue(ex.getCause() instanceof java.time.format.DateTimeParseException);
  }

  // ── optional variants ────────────────────────────────────────────────────────

  /**
   * An omitted filter is not a malformed one. JAX-RS hands an unsupplied {@code @QueryParam} over
   * as null, so rejecting null here would 400 every unfiltered request.
   */
  @Test
  void absentOptionalValuesMeanNoFilterRatherThanAnError() {
    assertNull(Parsing.optionalUuid(null, "storeId"));
    assertNull(Parsing.optionalUuid("   ", "storeId"));
    assertNull(Parsing.optionalInstant(null, "from"));
    assertNull(Parsing.optionalInstant("", "from"));
  }

  @Test
  void presentOptionalValuesAreParsed() {
    assertEquals(UUID.fromString(VALID_UUID), Parsing.optionalUuid(VALID_UUID, "storeId"));
    assertEquals(
        Instant.parse("2026-01-31T00:00:00Z"),
        Parsing.optionalInstant("2026-01-31T00:00:00Z", "from"));
  }

  /** Padding survives being pasted into a URL and says nothing about the caller's intent. */
  @Test
  void surroundingWhitespaceIsTolerated() {
    assertEquals(
        UUID.fromString(VALID_UUID), Parsing.optionalUuid("  " + VALID_UUID + " ", "storeId"));
    assertEquals(
        Instant.parse("2026-01-31T00:00:00Z"),
        Parsing.optionalInstant(" 2026-01-31T00:00:00Z\t", "from"));
  }

  /**
   * The reason the report resources stopped hand-rolling these: they threw
   * INVENTORY_INVALID_UUID/INVENTORY_INVALID_TIMESTAMP, so the same mistake carried a different
   * code depending on which endpoint the caller hit.
   */
  @Test
  void aPresentButMalformedOptionalValueUsesTheSameCodeAsTheRequiredParser() {
    assertEquals(
        ErrorCodes.INVALID_UUID,
        assertThrows(ApiException.class, () -> Parsing.optionalUuid("not-a-uuid", "actorId"))
            .code());
    assertEquals(
        ErrorCodes.INVALID_DATE,
        assertThrows(ApiException.class, () -> Parsing.optionalInstant("last-tuesday", "from"))
            .code());
  }

  /** Codes are compared by value across services, so they must stay plain interned literals. */
  @Test
  void codesAreTheSharedConstants() {
    assertSame(
        ErrorCodes.INVALID_UUID,
        assertThrows(ApiException.class, () -> Parsing.optionalUuid("x", "f")).code());
  }
}
