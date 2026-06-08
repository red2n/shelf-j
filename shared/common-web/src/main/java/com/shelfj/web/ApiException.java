package com.shelfj.web;

import java.util.List;

/**
 * Throw this from the service layer to return a controlled error with the right HTTP status and a
 * stable code.
 *
 * <p>Examples:
 *
 * <pre>{@code
 * throw ApiException.notFound("STORE_NOT_FOUND", "No store with that id");
 * throw ApiException.conflict("STORE_CODE_TAKEN", "Store code already used in this tenant");
 * throw ApiException.unprocessable("INVENTORY_INSUFFICIENT_STOCK", "Not enough stock to reserve");
 * }</pre>
 */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final List<String> details;

  public ApiException(int status, String code, String message, List<String> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public ApiException(
      int status, String code, String message, List<String> details, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<String> details() {
    return details;
  }

  public ErrorBody toErrorBody() {
    return ErrorBody.of(code, getMessage(), details);
  }

  // --- common factories (status codes per README §7.2) ---
  public static ApiException badRequest(String code, String message) {
    return new ApiException(400, code, message, List.of());
  }

  public static ApiException unauthorized(String code, String message) {
    return new ApiException(401, code, message, List.of());
  }

  public static ApiException forbidden(String code, String message) {
    return new ApiException(403, code, message, List.of());
  }

  public static ApiException notFound(String code, String message) {
    return new ApiException(404, code, message, List.of());
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(409, code, message, List.of());
  }

  /** Business-rule violation (well-formed request, but not allowed by domain rules). */
  public static ApiException unprocessable(String code, String message) {
    return new ApiException(422, code, message, List.of());
  }
}
