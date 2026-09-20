package com.storeql.web;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.spi.ExtendedExceptionMapper;

/**
 * Turns a request body that is not the JSON a resource expects — malformed, or a field of the wrong
 * type, such as a VAT rate sent as {@code "twenty"} — into a clean 400 {@code REQUEST_BODY_INVALID}
 * instead of a 500 (SJ-D57, golden rule #15).
 *
 * <p>Jersey's JSON-B reader catches the binding failure and throws a {@link ProcessingException}
 * around it, which is not a {@code WebApplicationException}, so it fell through to {@link
 * GenericExceptionMapper} as {@code 500 INTERNAL_ERROR} on every service.
 *
 * <p>Scope is deliberately narrow: {@link #isMappable} claims the exception only when a JSON or
 * JSON-B failure is in its causes <em>and</em> it was raised while the server read the request
 * entity. The same failure while a service reads another service's response is that service's
 * fault, not the caller's, and stays a 500.
 */
@Provider
public class RequestBodyExceptionMapper implements ExtendedExceptionMapper<ProcessingException> {

  private static final String REQUEST_ENTITY_READER =
      "org.glassfish.jersey.server.ContainerRequest";
  private static final java.util.Set<String> JSON_FAILURES =
      java.util.Set.of("jakarta.json.bind.JsonbException", "jakarta.json.JsonException");
  private static final int MAX_DEPTH = 10;

  private final java.util.Set<String> jsonFailures;

  /** The mapper Jersey registers: JSON-P and JSON-B failures. */
  public RequestBodyExceptionMapper() {
    this(JSON_FAILURES);
  }

  /** For tests: which exception classes count as a body that is not the JSON a request takes. */
  RequestBodyExceptionMapper(java.util.Set<String> jsonFailures) {
    this.jsonFailures = java.util.Set.copyOf(jsonFailures);
  }

  /**
   * @return {@code true} when a JSON or JSON-B failure is among {@code ex}'s causes and {@code ex}
   *     was thrown while the server was reading the request entity
   */
  @Override
  public boolean isMappable(ProcessingException ex) {
    return readingRequestEntity(ex) && jsonFailureIn(ex, jsonFailures);
  }

  /**
   * @param ex the binding failure (never echoed: its message names internal classes)
   * @return a {@code 400} envelope with {@link ErrorCodes#REQUEST_BODY_INVALID}
   */
  @Override
  public Response toResponse(ProcessingException ex) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(
                    ErrorCodes.REQUEST_BODY_INVALID,
                    "The request body is not valid JSON of the shape this request takes")))
        .build();
  }

  private static boolean readingRequestEntity(Throwable ex) {
    for (StackTraceElement frame : ex.getStackTrace()) {
      if (REQUEST_ENTITY_READER.equals(frame.getClassName())
          && "readEntity".equals(frame.getMethodName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean jsonFailureIn(Throwable ex, java.util.Set<String> failures) {
    Throwable cause = ex.getCause();
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
      for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
        if (failures.contains(type.getName())) return true;
      }
    }
    return false;
  }
}
