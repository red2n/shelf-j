package com.storeql.web;

import com.storeql.ids.Ids;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.UUID;

/**
 * Reads every {@code UUID} path, query and header parameter in every service as a UUIDv7, and
 * refuses anything else with {@code 400 INVALID_UUID}.
 *
 * <p>Without this, JAX-RS converts through {@code UUID.fromString}, which takes any version — and
 * {@code "1-1-1-1-1"}. An id that is not v7 cannot name anything StoreQL made, so it is refused at
 * the door rather than looked up. The refusal is a {@link BadRequestException} carrying the
 * envelope, because JAX-RS rethrows a web exception from a converter as it is, where any other
 * failure becomes a 404 that would say the id was merely not found.
 */
@Provider
public class UuidParamConverterProvider implements ParamConverterProvider {

  static final ParamConverter<UUID> CONVERTER =
      new ParamConverter<>() {
        @Override
        public UUID fromString(String value) {
          if (value == null) {
            return null;
          }
          try {
            return Ids.parse(value);
          } catch (Ids.InvalidIdException e) {
            throw new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(
                        ApiResponse.error(
                            ErrorBody.of(
                                ErrorCodes.INVALID_UUID, "A request identifier is not a UUIDv7")))
                    .build(),
                e);
          }
        }

        @Override
        public String toString(UUID value) {
          return value == null ? null : value.toString();
        }
      };

  @Override
  @SuppressWarnings("unchecked")
  public <T> ParamConverter<T> getConverter(
      Class<T> rawType, Type genericType, Annotation[] annotations) {
    return rawType == UUID.class ? (ParamConverter<T>) CONVERTER : null;
  }
}
