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

  /**
   * @param status HTTP status code to respond with, e.g. {@code 404}
   * @param code stable machine-readable error code, e.g. {@code "STORE_NOT_FOUND"}
   * @param message human-readable summary; safe to return to the client verbatim
   * @param details optional field-level detail messages (e.g. per-field validation errors); {@code
   *     null} is treated as empty
   */
  public ApiException(int status, String code, String message, List<String> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  /**
   * @param status HTTP status code to respond with, e.g. {@code 404}
   * @param code stable machine-readable error code, e.g. {@code "STORE_NOT_FOUND"}
   * @param message human-readable summary; safe to return to the client verbatim
   * @param details optional field-level detail messages; {@code null} is treated as empty
   * @param cause the underlying exception this API error wraps (logged server-side, never exposed
   *     in the response body)
   */
  public ApiException(
      int status, String code, String message, List<String> details, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  /**
   * @return the HTTP status code this exception should be mapped to
   */
  public int status() {
    return status;
  }

  /**
   * @return the stable machine-readable error code
   */
  public String code() {
    return code;
  }

  /**
   * @return optional field-level detail messages; never {@code null}, empty when none apply
   */
  public List<String> details() {
    return details;
  }

  /**
   * @return this exception rendered as the response-body {@link ErrorBody}
   */
  public ErrorBody toErrorBody() {
    return ErrorBody.of(code, getMessage(), details);
  }

  // --- common factories (status codes per docs/ARCHITECTURE.md §14) ---

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 400} exception with no field-level details
   */
  public static ApiException badRequest(String code, String message) {
    return new ApiException(400, code, message, List.of());
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 401} exception (no authenticated principal, or an invalid/expired token)
   */
  public static ApiException unauthorized(String code, String message) {
    return new ApiException(401, code, message, List.of());
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 403} exception (authenticated, but not permitted to perform this operation)
   */
  public static ApiException forbidden(String code, String message) {
    return new ApiException(403, code, message, List.of());
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 404} exception
   */
  public static ApiException notFound(String code, String message) {
    return new ApiException(404, code, message, List.of());
  }

  /**
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 409} exception (e.g. a unique-key clash such as a duplicate store code)
   */
  public static ApiException conflict(String code, String message) {
    return new ApiException(409, code, message, List.of());
  }

  /**
   * Business-rule violation (well-formed request, but not allowed by domain rules).
   *
   * @param code stable machine-readable error code
   * @param message human-readable summary
   * @return a {@code 422} exception
   */
  public static ApiException unprocessable(String code, String message) {
    return new ApiException(422, code, message, List.of());
  }
}
