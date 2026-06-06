package com.shelfj.web;

/**
 * The single response envelope every Shelf-J endpoint returns (README §7.1).
 *
 * <pre>{@code
 * { "data": {...}, "error": null, "meta": { "requestId": "...", "nextCursor": "..." } }
 * }</pre>
 *
 * On success: {@code data} set, {@code error} null. On failure: {@code data} null, {@code error} set.
 *
 * @param <T> payload type
 */
public record ApiResponse<T>(T data, ErrorBody error, Meta meta) {

    /** Pagination / trace metadata. {@code nextCursor} is null when there is no further page. */
    public record Meta(String requestId, String nextCursor) {
        public static Meta of(String requestId) {
            return new Meta(requestId, null);
        }
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Meta meta) {
        return new ApiResponse<>(data, null, meta);
    }

    public static <T> ApiResponse<T> error(ErrorBody error) {
        return new ApiResponse<>(null, error, null);
    }

    public static <T> ApiResponse<T> error(ErrorBody error, Meta meta) {
        return new ApiResponse<>(null, error, meta);
    }
}
