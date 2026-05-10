package com.frauddetection.alert.exception;

import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditTrustAttestationUnavailableException;
import com.frauddetection.alert.audit.InvalidAuditEventQueryException;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.audit.external.AuditEvidenceExportRejectedException;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublicationRequiredException;
import com.frauddetection.alert.fraudcase.FraudCaseActorUnavailableException;
import com.frauddetection.alert.fraudcase.FraudCaseConflictException;
import com.frauddetection.alert.fraudcase.FraudCaseIdempotencyConflictException;
import com.frauddetection.alert.fraudcase.FraudCaseIdempotencyInProgressException;
import com.frauddetection.alert.fraudcase.FraudCaseInvalidIdempotencyKeyException;
import com.frauddetection.alert.fraudcase.FraudCaseMissingIdempotencyKeyException;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseValidationException;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryLookupUnavailableException;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryNotFoundException;
import com.frauddetection.alert.governance.audit.GovernanceAuditActorUnavailableException;
import com.frauddetection.alert.governance.audit.GovernanceAuditDecision;
import com.frauddetection.alert.governance.audit.GovernanceAuditPersistenceUnavailableException;
import com.frauddetection.alert.governance.audit.InvalidGovernanceAuditRequestException;
import com.frauddetection.alert.governance.audit.InvalidGovernanceAuditDecisionException;
import com.frauddetection.alert.regulated.MissingIdempotencyKeyException;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

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
                        "Audit persistence is unavailable; mutation was not executed.",
                        List.of("reason:REJECTED_BEFORE_MUTATION")
                )
        );
    }

    @ExceptionHandler(PostCommitEvidenceIncompleteException.class)
    public ResponseEntity<ApiErrorResponse> handlePostCommitEvidenceIncomplete(PostCommitEvidenceIncompleteException exception) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                new ApiErrorResponse(
                        Instant.now(),
                        202,
                        "Accepted",
                        exception.getMessage(),
                        List.of("operation_status:COMMITTED_EVIDENCE_INCOMPLETE")
                )
        );
    }

    @ExceptionHandler(AuditTrustAttestationUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAuditTrustAttestationUnavailable(AuditTrustAttestationUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiErrorResponse(
                        Instant.now(),
                        503,
                        "Service Unavailable",
                        "Audit trust attestation is unavailable.",
                        List.of()
                )
        );
    }

    @ExceptionHandler(ExternalAuditAnchorPublicationRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleExternalAuditAnchorPublicationRequired(ExternalAuditAnchorPublicationRequiredException exception) {
        HttpStatus status = externalAnchorStatus(exception.reason());
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        Instant.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        "External audit anchor publication is required but unavailable.",
                        List.of("reason:" + exception.reason())
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

    @ExceptionHandler(ConflictingIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleConflictingIdempotencyKey(ConflictingIdempotencyKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiErrorResponse(
                        Instant.now(),
                        409,
                        "Conflict",
                        exception.getMessage(),
                        List.of("reason:IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD")
                )
        );
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingIdempotencyKey(MissingIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        exception.getMessage(),
                        List.of("reason:IDEMPOTENCY_KEY_REQUIRED")
                )
        );
    }

    @ExceptionHandler(GovernanceAuditActorUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleGovernanceAuditActorUnavailable(GovernanceAuditActorUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ApiErrorResponse(Instant.now(), 401, "Unauthorized", "Authentication is required.", List.of("reason:missing_credentials"))
        );
    }

    @ExceptionHandler(FraudCaseActorUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseActorUnavailable(FraudCaseActorUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ApiErrorResponse(Instant.now(), 401, "Unauthorized", "Authentication is required.", List.of("reason:missing_credentials"))
        );
    }

    @ExceptionHandler(FraudCaseNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseNotFound(FraudCaseNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiErrorResponse(Instant.now(), 404, "Not Found", exception.getMessage(), List.of("reason:FRAUD_CASE_NOT_FOUND"))
        );
    }

    @ExceptionHandler(FraudCaseConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseConflict(FraudCaseConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiErrorResponse(Instant.now(), 409, "Conflict", exception.getMessage(), List.of("reason:FRAUD_CASE_LIFECYCLE_CONFLICT"))
        );
    }

    @ExceptionHandler(FraudCaseValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseValidation(FraudCaseValidationException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", exception.getMessage(), List.of("reason:FRAUD_CASE_VALIDATION_FAILED"))
        );
    }

    @ExceptionHandler(FraudCaseMissingIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseMissingIdempotencyKey(FraudCaseMissingIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", exception.getMessage(), List.of("code:MISSING_IDEMPOTENCY_KEY"))
        );
    }

    @ExceptionHandler(FraudCaseInvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseInvalidIdempotencyKey(FraudCaseInvalidIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", exception.getMessage(), List.of("code:INVALID_IDEMPOTENCY_KEY"))
        );
    }

    @ExceptionHandler(FraudCaseIdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseIdempotencyConflict(FraudCaseIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiErrorResponse(Instant.now(), 409, "Conflict", exception.getMessage(), List.of("code:IDEMPOTENCY_KEY_CONFLICT"))
        );
    }

    @ExceptionHandler(FraudCaseIdempotencyInProgressException.class)
    public ResponseEntity<ApiErrorResponse> handleFraudCaseIdempotencyInProgress(FraudCaseIdempotencyInProgressException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiErrorResponse(Instant.now(), 409, "Conflict", exception.getMessage(), List.of("code:IDEMPOTENCY_KEY_IN_PROGRESS"))
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(Instant.now(), 400, "Bad Request", exception.getMessage(), List.of())
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(
                        Instant.now(),
                        400,
                        "Bad Request",
                        "Required request header is missing.",
                        List.of(exception.getHeaderName() + ": required")
                )
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        HttpStatus httpStatus = HttpStatus.resolve(status);
        String error = httpStatus == null ? exception.getStatusCode().toString() : httpStatus.getReasonPhrase();
        String reason = exception.getReason() == null ? error : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(
                new ApiErrorResponse(Instant.now(), status, error, reason, List.of())
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

    private HttpStatus externalAnchorStatus(String reason) {
        return switch (reason) {
            case "CONFLICT", "MISMATCH", "EXTERNAL_PAYLOAD_HASH_MISMATCH", "EXTERNAL_OBJECT_KEY_MISMATCH",
                 "EXTERNAL_ANCHOR_ID_MISMATCH", "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED" -> HttpStatus.CONFLICT;
            case "INVALID_ANCHOR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
