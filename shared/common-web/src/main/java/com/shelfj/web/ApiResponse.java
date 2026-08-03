package com.shelfj.web;

/**
 * The single response envelope every Shelf-J endpoint returns (docs/ARCHITECTURE.md §14).
 *
 * <pre>{@code
 * { "data": {...}, "error": null, "meta": { "requestId": "...", "nextCursor": "..." } }
 * }</pre>
 *
 * On success: {@code data} set, {@code error} null. On failure: {@code data} null, {@code error}
 * set.
 *
 * @param <T> payload type
 * @param data the success payload; {@code null} on failure
 * @param error the failure detail; {@code null} on success
 * @param meta pagination/trace metadata; may be {@code null} when there is nothing to report
 */
public record ApiResponse<T>(T data, ErrorBody error, Meta meta) {

  /**
   * Pagination / trace metadata.
   *
   * @param requestId correlation id echoed from {@link HttpHeaders#REQUEST_ID}
   * @param nextCursor opaque cursor for the next page ({@link Cursor}), or {@code null} when there
   *     is no further page
   */
  public record Meta(String requestId, String nextCursor) {

    /**
     * @param requestId correlation id to report
     * @return metadata with no next page (single-item responses, or the last page of a list)
     */
    public static Meta of(String requestId) {
      return new Meta(requestId, null);
    }
  }

  /**
   * @param data the success payload
   * @return an envelope with {@code data} set and no metadata
   */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, null, null);
  }

  /**
   * @param data the success payload
   * @param meta pagination/trace metadata to attach
   * @return an envelope with {@code data} and {@code meta} set
   */
  public static <T> ApiResponse<T> ok(T data, Meta meta) {
    return new ApiResponse<>(data, null, meta);
  }

  /**
   * @param error the failure detail
   * @return an envelope with {@code error} set and no metadata
   */
  public static <T> ApiResponse<T> error(ErrorBody error) {
    return new ApiResponse<>(null, error, null);
  }

  /**
   * @param error the failure detail
   * @param meta pagination/trace metadata to attach (e.g. {@code requestId} for correlation)
   * @return an envelope with {@code error} and {@code meta} set
   */
  public static <T> ApiResponse<T> error(ErrorBody error, Meta meta) {
    return new ApiResponse<>(null, error, meta);
  }
}
