package com.shelfj.web;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link ApiException} to the standard {@link ApiResponse} envelope with the intended HTTP
 * status. Registered automatically via {@code @Provider} (Helidon MP scans it).
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<ApiException> {

  /**
   * @param ex the API exception thrown by service-layer code
   * @return a response with {@code ex}'s status and an {@link ApiResponse#error} envelope
   */
  @Override
  public Response toResponse(ApiException ex) {
    return Response.status(ex.status())
        .type("application/json")
        .entity(ApiResponse.error(ex.toErrorBody()))
        .build();
  }
}
