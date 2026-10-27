package com.dietguide.shared;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Object details;

    public AppException(String code, String message, HttpStatus status, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Object details() {
        return details;
    }

    public static AppException unauthorized(String message) {
        return new AppException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED, null);
    }

    public static AppException notFound(String resource) {
        return new AppException("NOT_FOUND", resource + " not found", HttpStatus.NOT_FOUND, null);
    }

    public static AppException validation(String message) {
        return new AppException("VALIDATION_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY, null);
    }

    public static AppException idempotencyConflict() {
        return new AppException(
                "IDEMPOTENCY_CONFLICT",
                "The same idempotency key was used with a different payload",
                HttpStatus.CONFLICT,
                null
        );
    }

    public static AppException mealRevisionConflict(int currentRevision) {
        return new AppException(
                "MEAL_REVISION_CONFLICT",
                "base_revision is stale",
                HttpStatus.CONFLICT,
                java.util.Map.of("currentRevision", currentRevision)
        );
    }

    public static AppException aiInvalidOutput(String message, Object details) {
        return new AppException("AI_INVALID_OUTPUT", message, HttpStatus.BAD_GATEWAY, details);
    }

    public static AppException aiUnavailable(String message) {
        return new AppException("AI_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE, null);
    }
}
