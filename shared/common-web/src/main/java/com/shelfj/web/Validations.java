package com.shelfj.web;

import java.util.List;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Explicit Bean Validation that produces our standard envelope.
 *
 * <p>We validate in the resource via {@link #validate(Object)} instead of relying on JAX-RS {@code @Valid}, because
 * Helidon registers its own {@code ConstraintViolationException} mapper that returns a verbose, internals-leaking
 * body. Calling this ourselves throws {@link ApiException} with a clean {@code VALIDATION_FAILED} 400 (golden rule
 * #15, README §7.1/§7.2).</p>
 */
public final class Validations {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private Validations() {}

    /** Validate a bean; throw a 400 ApiException with field-level details if it fails. */
    public static <T> void validate(T bean) {
        if (bean == null) {
            throw ApiException.badRequest("BODY_REQUIRED", "Request body required");
        }
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(bean);
        if (violations.isEmpty()) {
            return;
        }
        List<String> details = violations.stream()
                .map(v -> leafField(v) + ": " + v.getMessage())
                .sorted()
                .toList();
        throw new ApiException(400, "VALIDATION_FAILED", "Request validation failed", details);
    }

    private static String leafField(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
