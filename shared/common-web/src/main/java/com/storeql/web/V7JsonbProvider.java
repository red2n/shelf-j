package com.storeql.web;

import com.storeql.ids.Ids;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Type;
import java.util.UUID;

/**
 * The JSON-B every service binds request bodies with: the default configuration, except that a
 * {@code UUID} field is read as a UUIDv7 and anything else is refused ({@code 400 INVALID_UUID},
 * through {@link RequestBodyExceptionMapper}).
 *
 * <p>Writing is unchanged. Reading is where an id from outside enters, and a body is the widest
 * door: 171 request fields are typed {@code UUID}.
 */
@Provider
public class V7JsonbProvider implements ContextResolver<Jsonb> {

  private static final Jsonb JSONB =
      JsonbBuilder.create(new JsonbConfig().withDeserializers(new V7UuidDeserializer()));

  @Override
  public Jsonb getContext(Class<?> type) {
    return JSONB;
  }

  /** A UUID field, read as a canonical UUIDv7. */
  static final class V7UuidDeserializer implements JsonbDeserializer<UUID> {
    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      // JSON-B hands a field's null to its deserializer too: an absent id stays absent.
      if (parser.currentEvent() == JsonParser.Event.VALUE_NULL) {
        return null;
      }
      return Ids.parse(parser.getString());
    }
  }
}
