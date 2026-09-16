package com.shelfj.web;

import java.util.List;

/**
 * An error as RFC 9457 problem details, with the platform's stable machine code and the envelope
 * members clients have read since the first release.
 *
 * <p>{@code type} is a URN for the code ({@code urn:shelfj:problem:CODE}), {@code title} the code
 * in words, {@code status} the HTTP status, {@code detail} the message written for a person, and
 * {@code instance} the request path. {@code code} and {@code details} are the platform's extension
 * members; {@code requestId} the correlation id; {@code error} and {@code meta} the legacy
 * envelope, so a client that reads {@code error.code} keeps working. Served as {@code
 * application/problem+json}.
 *
 * @param type a URN identifying the problem type by its code
 * @param title the code in words
 * @param status the HTTP status
 * @param detail the message for a person
 * @param instance the request path, or null outside a request
 * @param code the stable machine code
 * @param details field-level details, possibly empty
 * @param requestId the correlation id, or null
 * @param error the legacy envelope's error member
 * @param meta the legacy envelope's metadata, or null
 */
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    List<String> details,
    String requestId,
    ErrorBody error,
    ApiResponse.Meta meta) {

  public Problem {
    details = details == null ? List.of() : List.copyOf(details);
  }
}
