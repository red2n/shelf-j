package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidParseExceptionMapperTest {

  private final UuidParseExceptionMapper mapper = new UuidParseExceptionMapper();

  @Test
  void claimsMalformedUuidParse() {
    IllegalArgumentException ex = captureFromString("not-a-uuid");
    assertTrue(mapper.isMappable(ex), "should claim UUID.fromString failures");

    Response resp = mapper.toResponse(ex);
    assertEquals(400, resp.getStatus());
    ApiResponse<?> body = (ApiResponse<?>) resp.getEntity();
    assertEquals(ErrorCodes.INVALID_UUID, body.error().code());
  }

  @Test
  void claimsOverlongUuidParse() {
    // UUID.fromString throws a *different* message ("UUID string too large") for overlong input;
    // stack-frame detection catches it regardless of the JDK message wording.
    IllegalArgumentException ex = captureFromString("x".repeat(64));
    assertTrue(mapper.isMappable(ex));
  }

  @Test
  void declinesUnrelatedIllegalArgument() {
    // A genuine internal bug must still fall through to GenericExceptionMapper (500).
    IllegalArgumentException ex = new IllegalArgumentException("some other invariant violated");
    assertFalse(mapper.isMappable(ex));
  }

  private static IllegalArgumentException captureFromString(String bad) {
    try {
      UUID.fromString(bad);
      throw new AssertionError("expected UUID.fromString to throw for: " + bad);
    } catch (IllegalArgumentException e) {
      return e;
    }
  }
}
