package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SJ-D57: a request body that is not the JSON a request takes is the caller's 400; the same failure
 * reading another service's response stays a 500. The real JSON-B failure is proven end to end in
 * pricing-svc's VatRateIT; this pins the rules with a stand-in failure class.
 */
class RequestBodyExceptionMapperTest {

  /** Stands in for jakarta.json.bind.JsonbException. */
  static class BindingFailure extends RuntimeException {
    BindingFailure(String message) {
      super(message);
    }
  }

  /** A failure one step removed from the one the mapper knows, to prove subclasses count. */
  static class ParsingFailure extends BindingFailure {
    ParsingFailure(String message) {
      super(message);
    }
  }

  private final RequestBodyExceptionMapper mapper =
      new RequestBodyExceptionMapper(Set.of(BindingFailure.class.getName()));

  private static StackTraceElement frame(String type, String method) {
    return new StackTraceElement(type, method, type + ".java", 1);
  }

  private static ProcessingException thrown(Throwable cause, StackTraceElement... frames) {
    ProcessingException e =
        new ProcessingException("Error deserializing object from entity stream.", cause);
    e.setStackTrace(frames);
    return e;
  }

  private static final StackTraceElement READER =
      frame("org.glassfish.jersey.jsonb.internal.JsonBindingProvider", "readFrom");
  private static final StackTraceElement REQUEST =
      frame("org.glassfish.jersey.server.ContainerRequest", "readEntity");
  private static final StackTraceElement CLIENT_RESPONSE =
      frame("org.glassfish.jersey.client.InboundJaxrsResponse", "readEntity");

  @Test
  @DisplayName("A body that fails to bind while the request is read is a 400 that echoes nothing")
  void aBadRequestBodyIsTheCallers() {
    ProcessingException e =
        thrown(new BindingFailure("Unable to deserialize property 'rate'"), READER, REQUEST);
    assertTrue(mapper.isMappable(e));
    Response r = mapper.toResponse(e);
    assertEquals(400, r.getStatus());
    ApiResponse<?> body = (ApiResponse<?>) r.getEntity();
    assertEquals(ErrorCodes.REQUEST_BODY_INVALID, body.error().code());
    assertFalse(body.error().message().contains("rate"), "the binding message is never echoed");
    assertFalse(body.error().message().contains("deserializ"), "nor the reader's");
  }

  @Test
  @DisplayName("A subclass of the failure, or one wrapped deeper, still counts")
  void subclassesAndDepth() {
    assertTrue(mapper.isMappable(thrown(new ParsingFailure("unexpected char"), READER, REQUEST)));
    Throwable deep =
        new IllegalStateException(
            "outer", new RuntimeException("mid", new BindingFailure("inner")));
    assertTrue(mapper.isMappable(thrown(deep, REQUEST)));
  }

  @Test
  @DisplayName("The same failure reading another service's response stays a server fault")
  void aDownstreamResponseIsNotTheCallers() {
    assertFalse(
        mapper.isMappable(thrown(new BindingFailure("bad upstream"), READER, CLIENT_RESPONSE)));
    assertFalse(mapper.isMappable(thrown(new BindingFailure("no frames"))));
  }

  @Test
  @DisplayName("A request read that failed for any other reason is not a bad body")
  void otherFailuresAreNot() {
    assertFalse(mapper.isMappable(thrown(new IOException("connection reset"), READER, REQUEST)));
    assertFalse(mapper.isMappable(thrown(null, READER, REQUEST)));
    Throwable loop = new RuntimeException("a");
    Throwable chain = loop;
    for (int i = 0; i < 20; i++) chain = new RuntimeException("level " + i, chain);
    assertFalse(
        mapper.isMappable(thrown(chain, REQUEST)),
        "a long chain without the failure is not mapped");
  }

  @Test
  @DisplayName("The mapper Jersey registers knows JSON-P and JSON-B failures by name")
  void theRegisteredMapper() {
    RequestBodyExceptionMapper registered = new RequestBodyExceptionMapper();
    assertFalse(registered.isMappable(thrown(new BindingFailure("stand-in"), REQUEST)));
  }
}
