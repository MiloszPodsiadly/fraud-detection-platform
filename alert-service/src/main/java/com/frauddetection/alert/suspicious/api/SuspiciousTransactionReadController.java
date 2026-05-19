package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Objects;

@Validated
@RestController
@RequestMapping("/internal/suspicious-transactions")
public class SuspiciousTransactionReadController {

    private static final Logger log = LoggerFactory.getLogger(SuspiciousTransactionReadController.class);

    private final SuspiciousTransactionReadService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final AlertServiceMetrics metrics;
    private final SuspiciousTransactionQueryTelemetryClassifier queryTelemetryClassifier;
    private final SuspiciousTransactionQueryTelemetrySink queryTelemetrySink;

    public SuspiciousTransactionReadController(
            SuspiciousTransactionReadService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            AlertServiceMetrics metrics,
            SuspiciousTransactionQueryTelemetryClassifier queryTelemetryClassifier,
            SuspiciousTransactionQueryTelemetrySink queryTelemetrySink
    ) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.sensitiveReadAuditService = Objects.requireNonNull(sensitiveReadAuditService, "sensitiveReadAuditService is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.queryTelemetryClassifier = Objects.requireNonNull(queryTelemetryClassifier, "queryTelemetryClassifier is required");
        this.queryTelemetrySink = Objects.requireNonNull(queryTelemetrySink, "queryTelemetrySink is required");
    }

    @GetMapping
    @AuditedSensitiveRead
    public SuspiciousTransactionSliceResponse search(
            @RequestParam MultiValueMap<String, String> rawParams,
            HttpServletRequest request
    ) {
        long started = System.nanoTime();
        SuspiciousTransactionSearchQuery query;
        try {
            query = SuspiciousTransactionSearchQuery.from(rawParams);
        } catch (SuspiciousTransactionReadValidationException exception) {
            metrics.recordSuspiciousTransactionApiSearch("validation_error", "ANY", "ANY");
            recordQueryTelemetry(queryTelemetryClassifier.searchValidationError(
                    rawParams != null && rawParams.containsKey("cursor"),
                    elapsed(started)
            ));
            throw exception;
        }
        try {
            SuspiciousTransactionSliceResponse response = service.search(query);
            metrics.recordSuspiciousTransactionApiSearch("success", statusLabel(query), riskLevelLabel(query));
            recordQueryTelemetry(queryTelemetryClassifier.search(query, "success", response, elapsed(started)));
            // AuditedSensitiveRead is marker-only; explicit audit records the bounded slice result count.
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SEARCH,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    null,
                    response.content().size(),
                    request
            );
            return response;
        } catch (SuspiciousTransactionReadValidationException exception) {
            metrics.recordSuspiciousTransactionApiSearch("validation_error", statusLabel(query), riskLevelLabel(query));
            recordQueryTelemetry(queryTelemetryClassifier.search(query, "validation_error", -1, null, elapsed(started)));
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionApiSearch("error", statusLabel(query), riskLevelLabel(query));
            recordQueryTelemetry(queryTelemetryClassifier.search(query, "error", -1, null, elapsed(started)));
            throw exception;
        }
    }

    @GetMapping("/{suspiciousTransactionId}")
    @AuditedSensitiveRead
    public SuspiciousTransactionResponse findById(
            @PathVariable String suspiciousTransactionId,
            HttpServletRequest request
    ) {
        long started = System.nanoTime();
        try {
            SuspiciousTransactionResponse response = service.findById(suspiciousTransactionId)
                    .orElseThrow(() -> notFound(suspiciousTransactionId, request));
            metrics.recordSuspiciousTransactionApiRead("success", statusLabel(response), riskLevelLabel(response));
            recordQueryTelemetry(queryTelemetryClassifier.read("success", 1, elapsed(started)));
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    suspiciousTransactionId,
                    1,
                    request
            );
            return response;
        } catch (ResponseStatusException exception) {
            metrics.recordSuspiciousTransactionApiRead("not_found", "ANY", "ANY");
            recordQueryTelemetry(queryTelemetryClassifier.read("not_found", 0, elapsed(started)));
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionApiRead("error", "ANY", "ANY");
            recordQueryTelemetry(queryTelemetryClassifier.read("error", -1, elapsed(started)));
            throw exception;
        }
    }

    private ResponseStatusException notFound(String suspiciousTransactionId, HttpServletRequest request) {
        sensitiveReadAuditService.auditAttempt(
                ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                suspiciousTransactionId,
                ReadAccessAuditOutcome.REJECTED,
                request
        );
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Suspicious transaction not found.");
    }

    private String statusLabel(SuspiciousTransactionSearchQuery query) {
        return query.status() == null ? "ANY" : query.status().name();
    }

    private String riskLevelLabel(SuspiciousTransactionSearchQuery query) {
        return query.riskLevel() == null ? "ANY" : query.riskLevel().name();
    }

    private String statusLabel(SuspiciousTransactionResponse response) {
        return response.status() == null ? "ANY" : response.status().name();
    }

    private String riskLevelLabel(SuspiciousTransactionResponse response) {
        return response.riskLevel() == null ? "ANY" : response.riskLevel().name();
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private void recordQueryTelemetry(SuspiciousTransactionQueryTelemetrySnapshot snapshot) {
        try {
            queryTelemetrySink.record(snapshot);
        } catch (RuntimeException exception) {
            // Telemetry is diagnostic only and must never alter the read API response path.
            log.warn(
                    "SuspiciousTransaction query telemetry sink failed endpoint={} outcome={} queryShape={}",
                    snapshot == null ? "search" : snapshot.endpoint(),
                    snapshot == null ? "error" : snapshot.outcome(),
                    snapshot == null ? "unknown" : snapshot.queryShape()
            );
        }
    }
}
