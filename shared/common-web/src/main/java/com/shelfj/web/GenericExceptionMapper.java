package com.shelfj.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.glassfish.jersey.spi.ExtendedExceptionMapper;

/**
 * Catch-all mapper: any exception not already handled by {@link ApiExceptionMapper} becomes a
 * sanitized 500 envelope. Without this, an unexpected RuntimeException falls through to the
 * container's default error page — outside the response envelope and potentially leaking internal
 * details (golden rule: never leak stack/SQL). The full stack trace goes to the server log only.
 *
 * <p>{@link WebApplicationException}s are deliberately DECLINED via {@link
 * ExtendedExceptionMapper#isMappable}: Jersey throws {@code NotFoundException} for every unmatched
 * path, and mapping it here would convert Helidon's framework routes ({@code /health}, {@code
 * /metrics}) into JSON 404s — the unmatched request must stay unmapped so the WebServer falls
 * through to the observe routing. WAEs already carry a correct status and leak nothing.
 */
@Provider
public class GenericExceptionMapper implements ExtendedExceptionMapper<Throwable> {

  private static final Logger LOG = System.getLogger(GenericExceptionMapper.class.getName());

  @Override
  public boolean isMappable(Throwable ex) {
    return !(ex instanceof WebApplicationException);
  }

  @Override
  public Response toResponse(Throwable ex) {
    LOG.log(Level.ERROR, "Unhandled exception", ex);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .type(MediaType.APPLICATION_JSON)
        .entity(ApiResponse.error(ErrorBody.of("INTERNAL_ERROR", "An unexpected error occurred")))
        .build();
  }
}
