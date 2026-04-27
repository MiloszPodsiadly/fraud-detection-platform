package com.frauddetection.alert.exception;

import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.InvalidAuditEventQueryException;
import com.frauddetection.alert.audit.external.AuditEvidenceExportRejectedException;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryLookupUnavailableException;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryNotFoundException;
import com.frauddetection.alert.governance.audit.GovernanceAuditActorUnavailableException;
import com.frauddetection.alert.governance.audit.GovernanceAuditDecision;
import com.frauddetection.alert.governance.audit.GovernanceAuditPersistenceUnavailableException;
import com.frauddetection.alert.governance.audit.InvalidGovernanceAuditRequestException;
import com.frauddetection.alert.governance.audit.InvalidGovernanceAuditDecisionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class AlertServiceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertServiceExceptionHandler.class);

    @ExceptionHandler(AlertNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(AlertNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiErrorResponse(Instant.now(), 404, "Not Found", exception.getMessage(), List.of())
        );
    }

    @ExceptionHandler(GovernanceAdvisoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleGovernanceAdvisoryNotFound(GovernanceAdvisoryNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiErrorResponse(Instant.now(), 404, "Not Found", exception.getMessage(), List.of())
        );
    }

    @ExceptionHandler(InvalidGovernanceAuditDecisionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidGovernanceAuditDecision(InvalidGovernanceAuditDecisionException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        "Invalid governance audit decision.",
                        List.of("allowed: " + String.join(",", GovernanceAuditDecision.allowedValues()))
                )
        );
    }

    @ExceptionHandler(InvalidGovernanceAuditRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidGovernanceAuditRequest(InvalidGovernanceAuditRequestException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        "Invalid governance audit request.",
                        exception.details()
                )
        );
    }

    @ExceptionHandler({
            GovernanceAuditPersistenceUnavailableException.class,
            GovernanceAdvisoryLookupUnavailableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleGovernanceAuditUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiErrorResponse(
                        Instant.now(),
                        503,
                        "Service Unavailable",
                        "Governance audit persistence or advisory lookup is unavailable.",
                        List.of()
                )
        );
    }

    @ExceptionHandler(AuditPersistenceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAuditPersistenceUnavailable(AuditPersistenceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiErrorResponse(
                        Instant.now(),
                        503,
                        "Service Unavailable",
                        "Audit persistence is unavailable.",
                        List.of()
                )
        );
    }

    @ExceptionHandler(InvalidAuditEventQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAuditEventQuery(InvalidAuditEventQueryException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        "Invalid audit event query.",
                        exception.details()
                )
        );
    }

    @ExceptionHandler(AuditEvidenceExportRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleAuditEvidenceExportRejected(AuditEvidenceExportRejectedException exception) {
        return ResponseEntity.status(exception.status()).body(
                new ApiErrorResponse(
                        Instant.now(),
                        exception.status().value(),
                        exception.status().getReasonPhrase(),
                        exception.getMessage(),
                        exception.details()
                )
        );
    }

    @ExceptionHandler(GovernanceAuditActorUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleGovernanceAuditActorUnavailable(GovernanceAuditActorUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ApiErrorResponse(Instant.now(), 401, "Unauthorized", "Authentication is required.", List.of("reason:missing_credentials"))
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", "Validation failed.", details)
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", "Validation failed.", exception.getConstraintViolations().stream()
                        .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                        .toList())
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        "Validation failed.",
                        List.of(exception.getName() + ": invalid value")
                )
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", "Malformed JSON request.", List.of())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled alert-service exception.", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiErrorResponse(Instant.now(), 500, "Internal Server Error", "An unexpected error occurred.", List.of())
        );
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
