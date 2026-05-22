package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.observability.LinkedAlertContextMetricOutcome;
import com.frauddetection.alert.suspicious.api.observability.LinkedAlertContextMetricsRecorder;
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
    private final SuspiciousTransactionLinkedAlertContextService linkedAlertContextService;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final AlertServiceMetrics metrics;
    private final LinkedAlertContextMetricsRecorder linkedAlertContextMetricsRecorder;
    private final SuspiciousTransactionQueryTelemetryClassifier queryTelemetryClassifier;
    private final SuspiciousTransactionQueryTelemetrySink queryTelemetrySink;

    public SuspiciousTransactionReadController(
            SuspiciousTransactionReadService service,
            SuspiciousTransactionLinkedAlertContextService linkedAlertContextService,
            SensitiveReadAuditService sensitiveReadAuditService,
            AlertServiceMetrics metrics,
            LinkedAlertContextMetricsRecorder linkedAlertContextMetricsRecorder,
            SuspiciousTransactionQueryTelemetryClassifier queryTelemetryClassifier,
            SuspiciousTransactionQueryTelemetrySink queryTelemetrySink
    ) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.linkedAlertContextService = Objects.requireNonNull(linkedAlertContextService, "linkedAlertContextService is required");
        this.sensitiveReadAuditService = Objects.requireNonNull(sensitiveReadAuditService, "sensitiveReadAuditService is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.linkedAlertContextMetricsRecorder = Objects.requireNonNull(
                linkedAlertContextMetricsRecorder,
                "linkedAlertContextMetricsRecorder is required"
        );
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

    @GetMapping("/summary")
    @AuditedSensitiveRead
    public SuspiciousTransactionSummaryResponse summary(HttpServletRequest request) {
        try {
            SuspiciousTransactionSummaryResponse response = service.summary();
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    null,
                    1,
                    request
            );
            recordSummaryMetric(response);
            return response;
        } catch (RuntimeException exception) {
            recordSummaryMetric("error", SuspiciousTransactionSummaryFreshness.UNAVAILABLE);
            throw exception;
        }
    }

    @GetMapping(value = "/{suspiciousTransactionId}/linked-alert", params = "alertId")
    @AuditedSensitiveRead
    public AlertLinkedContextResponse rejectClientSelectedAlertId(
            @PathVariable String suspiciousTransactionId,
            HttpServletRequest request
    ) {
        recordLinkedAlertContextMetric(LinkedAlertContextMetricOutcome.VALIDATION_ERROR);
        sensitiveReadAuditService.auditAttempt(
                ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT,
                ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                suspiciousTransactionId,
                ReadAccessAuditOutcome.REJECTED,
                request
        );
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alertId query parameter is not accepted.");
    }

    @GetMapping(value = "/{suspiciousTransactionId}/linked-alert", params = "!alertId")
    @AuditedSensitiveRead
    public AlertLinkedContextResponse linkedAlertContext(
            @PathVariable String suspiciousTransactionId,
            HttpServletRequest request
    ) {
        AlertLinkedContextResponse response;
        try {
            response = linkedAlertContextService.resolveLinkedAlertContext(suspiciousTransactionId);
        } catch (SuspiciousTransactionLinkedAlertContextNotFoundException exception) {
            recordLinkedAlertContextMetric(LinkedAlertContextMetricOutcome.SUSPICIOUS_TRANSACTION_NOT_FOUND);
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    suspiciousTransactionId,
                    ReadAccessAuditOutcome.REJECTED,
                    request
            );
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Suspicious transaction not found.");
        } catch (RuntimeException exception) {
            recordLinkedAlertContextMetric(LinkedAlertContextMetricOutcome.ERROR);
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    suspiciousTransactionId,
                    ReadAccessAuditOutcome.FAILED,
                    request
            );
            return AlertLinkedContextResponse.temporarilyUnavailable();
        }
        recordLinkedAlertContextMetric(linkedAlertOutcome(response.state()));
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT,
                ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                suspiciousTransactionId,
                response.state() == LinkedAlertContextState.LINKED_ALERT_AVAILABLE ? 1 : 0,
                request
        );
        return response;
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

    private void recordSummaryMetric(SuspiciousTransactionSummaryResponse response) {
        SuspiciousTransactionSummaryFreshness freshness = response.freshness();
        String outcome = switch (freshness) {
            case FRESH -> "success";
            case STALE -> "stale";
            case UNAVAILABLE -> "unavailable";
        };
        recordSummaryMetric(outcome, freshness);
    }

    private void recordSummaryMetric(String outcome, SuspiciousTransactionSummaryFreshness freshness) {
        try {
            metrics.recordSuspiciousTransactionSummaryRead(outcome, freshness == null ? "unavailable" : freshness.name());
        } catch (RuntimeException exception) {
            log.warn(
                    "SuspiciousTransaction summary metric recording failed outcome={} freshness={}",
                    outcome,
                    freshness == null ? "UNAVAILABLE" : freshness.name()
            );
        }
    }

    private LinkedAlertContextMetricOutcome linkedAlertOutcome(LinkedAlertContextState state) {
        if (state == null) {
            return LinkedAlertContextMetricOutcome.TEMPORARILY_UNAVAILABLE;
        }
        return switch (state) {
            case LINKED_ALERT_AVAILABLE -> LinkedAlertContextMetricOutcome.AVAILABLE;
            case NO_LINKED_ALERT -> LinkedAlertContextMetricOutcome.NO_LINKED_ALERT;
            case LINKED_ALERT_NOT_FOUND -> LinkedAlertContextMetricOutcome.LINKED_ALERT_NOT_FOUND;
            case LINKED_ALERT_RELATIONSHIP_MISMATCH -> LinkedAlertContextMetricOutcome.RELATIONSHIP_MISMATCH;
            case TEMPORARILY_UNAVAILABLE -> LinkedAlertContextMetricOutcome.TEMPORARILY_UNAVAILABLE;
        };
    }

    private void recordLinkedAlertContextMetric(LinkedAlertContextMetricOutcome outcome) {
        LinkedAlertContextMetricOutcome boundedOutcome = outcome == null
                ? LinkedAlertContextMetricOutcome.ERROR
                : outcome;
        try {
            // Recorder implementations should be fail-safe. This controller guard is defense-in-depth for custom recorder implementations and must keep the read API response independent from telemetry failures. Warnings must remain bounded and must not include request path, identifiers, or exception messages.
            linkedAlertContextMetricsRecorder.record(boundedOutcome);
        } catch (RuntimeException exception) {
            log.warn("Linked alert context metric recording failed outcome={}", boundedOutcome.label());
        }
    }
}
