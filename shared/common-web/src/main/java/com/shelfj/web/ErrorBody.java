package com.shelfj.web;

import java.util.List;

/**
 * Machine-readable error payload (docs/ARCHITECTURE.md §14).
 *
 * @param code stable, machine-readable code, e.g. {@code "INVENTORY_INSUFFICIENT_STOCK"} — never
 *     changes meaning
 * @param message human-readable summary (safe to show; never leaks stack traces or SQL)
 * @param details optional field-level validation messages
 */
public record ErrorBody(String code, String message, List<String> details) {

  public ErrorBody {
    details = details == null ? List.of() : List.copyOf(details);
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return an error body with no field-level details
   */
  public static ErrorBody of(String code, String message) {
    return new ErrorBody(code, message, List.of());
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @param details field-level detail messages; {@code null} is treated as empty
   * @return an error body carrying the given details
   */
  public static ErrorBody of(String code, String message, List<String> details) {
    return new ErrorBody(code, message, details == null ? List.of() : List.copyOf(details));
  }
}
