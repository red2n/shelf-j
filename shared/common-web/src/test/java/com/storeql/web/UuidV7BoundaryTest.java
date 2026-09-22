package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Every door an id comes in by, in every service: a path, query or header parameter, a request
 * body, the identity headers the gateway stamps, a string parsed by hand — and the Idempotency-Key.
 * Only an RFC 9562 UUIDv7 in canonical form gets through; the refusal is a 400 that says so.
 */
class UuidV7BoundaryTest {

  private static final String V7 = "01a0905d-7082-7518-9ec6-aee90d72a43e";
  private static final String V4 = "919108f7-52d1-4320-9bac-f847db4148a8";

  /** A request body with an id in it. */
  public record Body(UUID storeId, String note) {}

  @Test
  @DisplayName("A path, query or header parameter is read only as a UUIDv7")
  void parameters() {
    var converter =
        new UuidParamConverterProvider()
            .getConverter(UUID.class, UUID.class, new java.lang.annotation.Annotation[0]);
    assertEquals(UUID.class, converter.fromString(V7).getClass());
    assertNull(converter.fromString(null), "an absent parameter stays absent");
    for (String bad : new String[] {V4, "00000000-0000-0000-0000-000000000000", "1-1-1-1-1", "x"}) {
      BadRequestException e =
          assertThrows(BadRequestException.class, () -> converter.fromString(bad));
      assertEquals(400, e.getResponse().getStatus());
      assertTrue(e.getResponse().getEntity().toString().contains(ErrorCodes.INVALID_UUID), bad);
    }
    assertNull(
        new UuidParamConverterProvider()
            .getConverter(String.class, String.class, new java.lang.annotation.Annotation[0]),
        "only UUID parameters are this converter's");
  }

  @Test
  @DisplayName("A body binds a UUID field only when it is a UUIDv7, and says so in words")
  void bodies() {
    var jsonb = new V7JsonbProvider().getContext(Body.class);
    Body ok = jsonb.fromJson("{\"storeId\":\"" + V7 + "\",\"note\":\"n\"}", Body.class);
    assertEquals(Ids.parse(V7), ok.storeId());
    assertNull(jsonb.fromJson("{\"storeId\":null}", Body.class).storeId(), "null stays null");
    JsonbException refused =
        assertThrows(
            JsonbException.class, () -> jsonb.fromJson("{\"storeId\":\"" + V4 + "\"}", Body.class));
    Throwable cause = refused;
    while (cause != null && !(cause instanceof Ids.InvalidIdException)) cause = cause.getCause();
    assertTrue(cause instanceof Ids.InvalidIdException, "the refusal carries why");
    // And the mapper turns it into the same 400 a path parameter gets.
    jakarta.ws.rs.ProcessingException wrapped =
        new jakarta.ws.rs.ProcessingException(
            "Error deserializing object from entity stream.", refused);
    Response r =
        new RequestBodyExceptionMapper(java.util.Set.of(JsonbException.class.getName()))
            .toResponse(wrapped);
    assertEquals(400, r.getStatus());
    assertTrue(r.getEntity().toString().contains(ErrorCodes.INVALID_UUID));
  }

  @Test
  @DisplayName("A string parsed by hand is held to the same rule, naming the field")
  void parsedByHand() {
    assertEquals(Ids.parse(V7), Parsing.uuid(V7, "storeId"));
    ApiException e = assertThrows(ApiException.class, () -> Parsing.uuid(V4, "storeId"));
    assertEquals(ErrorCodes.INVALID_UUID, e.code());
    assertTrue(e.getMessage().contains("storeId must be a UUIDv7"), e.getMessage());
    Ids.InvalidIdException invalid =
        assertThrows(Ids.InvalidIdException.class, () -> Ids.parse(V4));
    assertTrue(new UuidParseExceptionMapper().isMappable(invalid), "mapped wherever it escapes");
  }

  @Test
  @DisplayName("An Idempotency-Key is refused unless it is a UUIDv7; none at all is left alone")
  void idempotencyKeys() {
    IdempotencyKeyFilter filter = new IdempotencyKeyFilter();
    for (String bad :
        new String[] {"pos-1757590000000-order", "sf-1757590000000", V4, "", "k6-abc"}) {
      ContainerRequestContext req = mock(ContainerRequestContext.class);
      when(req.getHeaderString(HttpHeaders.IDEMPOTENCY_KEY)).thenReturn(bad);
      filter.filter(req);
      ArgumentCaptor<Response> refused = ArgumentCaptor.forClass(Response.class);
      verify(req).abortWith(refused.capture());
      assertEquals(400, refused.getValue().getStatus(), bad);
      assertTrue(
          refused.getValue().getEntity().toString().contains(ErrorCodes.IDEMPOTENCY_KEY_INVALID));
    }
    for (String fine :
        new String[] {V7, V7.toUpperCase(Locale.ROOT), Ids.newId().toString(), null}) {
      ContainerRequestContext req = mock(ContainerRequestContext.class);
      MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
      when(req.getHeaders()).thenReturn(headers);
      when(req.getHeaderString(HttpHeaders.IDEMPOTENCY_KEY)).thenReturn(fine);
      filter.filter(req);
      verify(req, never()).abortWith(any());
      // What passes is handed on in the one form every table holds: canonical lowercase.
      if (fine != null) {
        assertEquals(
            fine.toLowerCase(Locale.ROOT), headers.getFirst(HttpHeaders.IDEMPOTENCY_KEY), fine);
      }
    }
  }

  @Test
  @DisplayName("A key in a body is held to the same rule as the header, and comes back canonical")
  void bodyKeys() {
    assertEquals(V7, IdempotencyKeys.effective(null, V7.toUpperCase(Locale.ROOT)));
    assertEquals(V7, IdempotencyKeys.effective(V7, "ignored when the header has one"));
    assertNull(IdempotencyKeys.effective(null, " "));
    ApiException refused =
        assertThrows(ApiException.class, () -> IdempotencyKeys.effective(null, "idem-replay-1"));
    assertEquals(400, refused.status());
    assertEquals(ErrorCodes.IDEMPOTENCY_KEY_INVALID, refused.code());
    assertThrows(ApiException.class, () -> IdempotencyKeys.effective(V4, null));
  }
}
