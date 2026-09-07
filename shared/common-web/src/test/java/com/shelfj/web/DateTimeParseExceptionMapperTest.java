package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

class DateTimeParseExceptionMapperTest {

  private final DateTimeParseExceptionMapper mapper = new DateTimeParseExceptionMapper();

  @Test
  void claimsMalformedInstantAsBadRequest() {
    // The exact input that produced a 500 from pricing-svc against the running stack: the price
    // list's effectiveFrom was documented as a date but parsed as an instant.
    DateTimeParseException ex = captureInstant("2026-01-01");
    assertTrue(mapper.isMappable(ex), "should claim Instant.parse failures");

    Response resp = mapper.toResponse(ex);
    assertEquals(400, resp.getStatus());
    ApiResponse<?> body = (ApiResponse<?>) resp.getEntity();
    assertEquals(ErrorCodes.INVALID_DATE, body.error().code());
  }

  @Test
  void claimsMalformedLocalDate() {
    assertTrue(mapper.isMappable(captureDate("not-a-date")));
  }

  /**
   * The response must not echo the exception message: it carries the caller's raw input, and the
   * envelope contract is that errors never leak internals.
   */
  @Test
  void doesNotEchoTheOffendingValue() {
    Response resp = mapper.toResponse(captureInstant("2026-01-01"));
    ApiResponse<?> body = (ApiResponse<?>) resp.getEntity();
    assertFalse(body.error().message().contains("2026-01-01"));
  }

  /**
   * A date that fails to parse inside a repository is a corrupt stored value, not caller input —
   * that is a genuine server fault and must stay a 500 rather than blaming the client.
   */
  @Test
  void declinesParseFailuresFromRepositoryAndMessagingCode() {
    assertFalse(mapper.isMappable(withTopFrame("com.shelfj.cart.repo.CartRepository", "mapCart")));
    assertFalse(
        mapper.isMappable(
            withTopFrame("com.shelfj.inventory.messaging.GoodsReceivedHandler", "handle")));
  }

  /** A parse in ordinary service or API code is caller input, so it is claimed. */
  @Test
  void claimsParseFailuresFromServiceCode() {
    assertTrue(
        mapper.isMappable(
            withTopFrame("com.shelfj.pricing.service.PricingService", "createPriceList")));
  }

  private static DateTimeParseException captureInstant(String raw) {
    try {
      Instant.parse(raw);
      throw new AssertionError("expected " + raw + " to fail parsing");
    } catch (DateTimeParseException e) {
      return e;
    }
  }

  private static DateTimeParseException captureDate(String raw) {
    try {
      LocalDate.parse(raw);
      throw new AssertionError("expected " + raw + " to fail parsing");
    } catch (DateTimeParseException e) {
      return e;
    }
  }

  /** A parse exception whose stack begins in the named class, to exercise {@code isMappable}. */
  private static DateTimeParseException withTopFrame(String className, String method) {
    DateTimeParseException ex = new DateTimeParseException("bad date", "x", 0);
    ex.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("java.time.format.DateTimeFormatter", "parse", null, -1),
          new StackTraceElement(className, method, null, -1),
        });
    return ex;
  }
}
