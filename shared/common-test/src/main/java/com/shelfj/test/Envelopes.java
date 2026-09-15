package com.shelfj.test;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Reading the response envelope ({@code {data, error, meta}}) in an integration test, and one value
 * out of the database beside it. Every service's ITs used to carry their own copy of these.
 */
public final class Envelopes {

  private Envelopes() {}

  /**
   * The {@code data} object of a 201.
   *
   * @throws AssertionError with the body when the status is anything else
   */
  public static JsonObject created(Response r) {
    return object(r, 201);
  }

  /** The {@code data} object of a 200. */
  public static JsonObject ok(Response r) {
    return object(r, 200);
  }

  /** The {@code data} array of a 200. */
  public static JsonArray okArray(Response r) {
    return parse(bodyOf(r, 200)).getJsonArray("data");
  }

  /** The {@code data} object of a response with the expected status. */
  public static JsonObject object(Response r, int status) {
    return parse(bodyOf(r, status)).getJsonObject("data");
  }

  /**
   * The body of a response with the expected status.
   *
   * @throws AssertionError naming the status and body otherwise
   */
  public static String bodyOf(Response r, int status) {
    String body = r.readEntity(String.class);
    if (r.getStatus() != status) {
      throw new AssertionError("expected " + status + " but was " + r.getStatus() + ": " + body);
    }
    return body;
  }

  public static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  /**
   * The first element whose string field holds the value.
   *
   * @throws AssertionError naming the array when there is none
   */
  public static JsonObject find(JsonArray array, String key, String value) {
    for (JsonObject o : array.getValuesAs(JsonObject.class)) {
      if (value.equals(o.getString(key, null))) return o;
    }
    throw new AssertionError("no element with " + key + "=" + value + " in " + array);
  }

  /**
   * Runs one statement against the test database — to backdate a row, say, so a purge finds it.
   *
   * @return the rows affected
   */
  public static int exec(PostgresSupport pg, String sql) {
    try (var c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        var st = c.createStatement()) {
      return st.executeUpdate(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * One value from the test database, as a string, or null when the query returns no row or a null.
   */
  public static String scalar(PostgresSupport pg, String sql) {
    try (var c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      if (!rs.next()) return null;
      Object value = rs.getObject(1);
      return value == null ? null : value.toString();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
