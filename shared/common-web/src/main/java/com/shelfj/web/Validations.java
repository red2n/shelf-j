package com.shelfj.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;

/**
 * Explicit Bean Validation that produces our standard envelope.
 *
 * <p>We validate in the resource via {@link #validate(Object)} instead of relying on JAX-RS
 * {@code @Valid}, because Helidon registers its own {@code ConstraintViolationException} mapper
 * that returns a verbose, internals-leaking body. Calling this ourselves throws {@link
 * ApiException} with a clean {@code VALIDATION_FAILED} 400 (golden rule #15, docs/ARCHITECTURE.md
 * §14).
 */
public final class Validations {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  private Validations() {}

  /**
   * Validate a bean against its Bean Validation ({@code jakarta.validation}) annotations.
   *
   * @param bean the DTO to validate, typically a deserialized request body
   * @throws ApiException 400 {@link ErrorCodes#BODY_REQUIRED} if {@code bean} is {@code null}; 400
   *     {@link ErrorCodes#VALIDATION_FAILED} with one {@code "<field>: <message>"} detail per
   *     failed constraint, sorted, if validation fails
   */
  public static <T> void validate(T bean) {
    if (bean == null) {
      throw ApiException.badRequest(ErrorCodes.BODY_REQUIRED, "Request body required");
    }
    Set<ConstraintViolation<T>> violations = VALIDATOR.validate(bean);
    if (violations.isEmpty()) {
      return;
    }
    List<String> details =
        violations.stream().map(v -> leafField(v) + ": " + v.getMessage()).sorted().toList();
    throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "Request validation failed", details);
  }

  /**
   * @param v a constraint violation
   * @return just the last path segment of {@code v}'s property path, e.g. {@code "email"} rather
   *     than {@code "address.email"}
   */
  private static String leafField(ConstraintViolation<?> v) {
    String path = v.getPropertyPath().toString();
    int dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1) : path;
  }
}
