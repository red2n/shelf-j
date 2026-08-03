package com.shelfj.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Shared parsers for path/query/body string fields that should be a UUID or date — every service
 * was hand-rolling the same try/catch around {@code UUID.fromString}/{@code LocalDate.parse} with
 * its own copy of the {@code INVALID_UUID}/{@code INVALID_DATE} literal. Centralized here so the
 * error code, status, and message wording stay identical everywhere.
 */
public final class Parsing {

  private Parsing() {}

  /**
   * Parse a UUID field.
   *
   * @param value the raw string to parse, e.g. from a path/query/body field
   * @param field the field name, used only to build the error message (e.g. {@code "storeId"})
   * @return the parsed {@link UUID}
   * @throws ApiException 400 {@link ErrorCodes#INVALID_UUID} if {@code value} is {@code null} or
   *     not a valid UUID
   */
  public static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      throw new ApiException(400, ErrorCodes.INVALID_UUID, field + " must be a UUID", List.of(), e);
    }
  }

  /**
   * Parse a {@code yyyy-MM-dd} date field.
   *
   * @param value the raw string to parse
   * @param field the field name, used only to build the error message
   * @return the parsed {@link LocalDate}
   * @throws ApiException 400 {@link ErrorCodes#INVALID_DATE} if {@code value} is {@code null} or
   *     not in {@code yyyy-MM-dd} format
   */
  public static LocalDate date(String value, String field) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw new ApiException(
          400, ErrorCodes.INVALID_DATE, field + " must be yyyy-MM-dd", List.of(), e);
    }
  }

  /**
   * Parse an ISO-8601 instant field.
   *
   * @param value the raw string to parse, e.g. {@code "2025-01-01T00:00:00Z"}
   * @param field the field name, used only to build the error message
   * @return the parsed {@link Instant}
   * @throws ApiException 400 {@link ErrorCodes#INVALID_DATE} if {@code value} is {@code null} or
   *     not a valid ISO-8601 instant
   */
  public static Instant instant(String value, String field) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw new ApiException(
          400,
          ErrorCodes.INVALID_DATE,
          field + " must be ISO-8601 (e.g. 2025-01-01T00:00:00Z)",
          List.of(),
          e);
    }
  }
}
