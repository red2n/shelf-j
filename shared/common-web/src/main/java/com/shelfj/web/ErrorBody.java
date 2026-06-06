package com.shelfj.web;

import java.util.List;

/**
 * Machine-readable error payload (README §7.2).
 *
 * @param code    stable, machine-readable code, e.g. {@code "INVENTORY_INSUFFICIENT_STOCK"} — never changes meaning
 * @param message human-readable summary (safe to show; never leaks stack traces or SQL)
 * @param details optional field-level validation messages
 */
public record ErrorBody(String code, String message, List<String> details) {

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, List.of());
    }

    public static ErrorBody of(String code, String message, List<String> details) {
        return new ErrorBody(code, message, details == null ? List.of() : List.copyOf(details));
    }
}
