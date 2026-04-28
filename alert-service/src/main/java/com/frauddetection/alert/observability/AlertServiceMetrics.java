package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.security.error.SecurityFailureClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AlertServiceMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger governanceAnalyticsWindowDays = new AtomicInteger(0);
    private final AtomicLong auditChainHeadHashFingerprint = new AtomicLong(0);
    private final AtomicLong auditLastAnchorHashFingerprint = new AtomicLong(0);
    private final AtomicInteger auditIntegrityValid = new AtomicInteger(0);
    private final AtomicInteger auditIntegrityInvalid = new AtomicInteger(0);

    public AlertServiceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("fraud_ml_governance_analytics_window_days", governanceAnalyticsWindowDays, AtomicInteger::get)
                .register(meterRegistry);
        Gauge.builder("fraud_audit_chain_head_hash", auditChainHeadHashFingerprint, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_audit_last_anchor_hash", auditLastAnchorHashFingerprint, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_audit_integrity_status", auditIntegrityValid, AtomicInteger::get)
                .tag("status", "VALID")
                .register(meterRegistry);
        Gauge.builder("fraud_audit_integrity_status", auditIntegrityInvalid, AtomicInteger::get)
                .tag("status", "INVALID")
                .register(meterRegistry);
    }

    public void recordAnalystDecisionSubmitted() {
        counter("fraud.alert.decision.submissions", "outcome", "success").increment();
    }

    public void recordFraudCaseUpdated() {
        counter("fraud.alert.fraud_case.updates", "outcome", "success").increment();
    }

    public void recordAuditEventPublished(AuditAction action, AuditOutcome outcome) {
        counter(
                "fraud.alert.audit.events",
                "action", normalize(action),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordPlatformAuditEventPersisted(AuditAction eventType, AuditOutcome outcome) {
        counter(
                "fraud_platform_audit_events_persisted_total",
                "event_type", normalize(eventType),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordPlatformAuditPersistenceFailure(AuditAction eventType) {
        counter(
                "fraud_platform_audit_persistence_failures_total",
                "event_type", normalize(eventType)
        ).increment();
    }

    public void recordPlatformAuditAnchorWriteFailure() {
        counter("fraud_platform_audit_anchor_write_failures_total").increment();
    }

    public void recordPlatformAuditChainConflict() {
        counter("fraud_platform_audit_chain_conflicts_total").increment();
    }

    public void recordPlatformAuditReadRequest(String status) {
        counter(
                "fraud_platform_audit_read_requests_total",
                "status", normalizeAvailabilityStatus(status)
        ).increment();
    }

    public void recordAuditIntegrityCheck(String status) {
        counter(
                "fraud_platform_audit_integrity_checks_total",
                "status", normalizeIntegrityStatus(status)
        ).increment();
        counter(
                "fraud_platform_audit_integrity_check_total",
                "status", normalizeIntegrityStatus(status)
        ).increment();
    }

    public void recordForensicAuditIntegrityCheck(String status) {
        counter(
                "fraud_audit_integrity_check_total",
                "status", normalizeIntegrityStatus(status)
        ).increment();
    }

    public void recordAuditIntegrityViolation(String violationType) {
        counter(
                "fraud_platform_audit_integrity_violations_total",
                "violation_type", normalizeIntegrityViolationType(violationType)
        ).increment();
    }

    public void recordForensicAuditIntegrityViolation(String violationType) {
        counter(
                "fraud_audit_integrity_violation_total",
                "violation_type", normalizeIntegrityViolationType(violationType)
        ).increment();
    }

    public void recordAuditIntegritySnapshot(String status, String chainHeadHash, String lastAnchorHash) {
        auditChainHeadHashFingerprint.set(hashFingerprint(chainHeadHash));
        auditLastAnchorHashFingerprint.set(hashFingerprint(lastAnchorHash));
        boolean valid = "VALID".equals(status) || "PARTIAL".equals(status);
        auditIntegrityValid.set(valid ? 1 : 0);
        auditIntegrityInvalid.set("INVALID".equals(status) ? 1 : 0);
    }

    public void recordExternalAnchorPublished(String sink, String status) {
        counter(
                "fraud_platform_audit_external_anchor_published_total",
                "sink", normalizeExternalSink(sink),
                "status", normalizeExternalAnchorPublishStatus(status)
        ).increment();
    }

    public void recordExternalAnchorPublishFailed(String sink, String reason) {
        counter(
                "fraud_platform_audit_external_anchor_publish_failed_total",
                "sink", normalizeExternalSink(sink),
                "reason", normalizeExternalAnchorFailureReason(reason)
        ).increment();
    }

    public void recordExternalAnchorLag(Duration lag) {
        Timer.builder("fraud_platform_audit_external_anchor_lag_seconds")
                .register(meterRegistry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public void recordExternalIntegrityCheck(String status) {
        counter(
                "fraud_platform_audit_external_integrity_checks_total",
                "status", normalizeIntegrityStatus(status)
        ).increment();
    }

    public void recordExternalAnchorOperationRetry(String operation) {
        counter(
                "fraud_platform_audit_external_anchor_retry_total",
                "operation", normalizeExternalAnchorOperation(operation)
        ).increment();
    }

    public void recordExternalAnchorOperationTimeout(String operation) {
        counter(
                "fraud_platform_audit_external_anchor_timeout_total",
                "operation", normalizeExternalAnchorOperation(operation)
        ).increment();
    }

    public void recordExternalAnchorOperationFailure(String operation) {
        counter(
                "fraud_platform_audit_external_anchor_operation_failure_total",
                "operation", normalizeExternalAnchorOperation(operation)
        ).increment();
    }

    public void recordExternalAnchorHeadScanDepth(int scannedKeys) {
        DistributionSummary.builder("fraud_platform_audit_external_anchor_head_scan_depth")
                .register(meterRegistry)
                .record(Math.max(0, scannedKeys));
    }

    public void recordExternalTamperingDetected(String reason) {
        counter(
                "fraud_platform_audit_external_tampering_detected_total",
                "reason", normalizeExternalAnchorFailureReason(reason)
        ).increment();
    }

    public void recordEvidenceExport(String status) {
        counter(
                "fraud_platform_audit_evidence_exports_total",
                "status", normalizeAvailabilityStatus(status)
        ).increment();
    }

    public void recordEvidenceExportRateLimited() {
        counter("fraud_platform_audit_evidence_export_rate_limited_total").increment();
    }

    public void recordEvidenceExportRepeatedFingerprint() {
        counter("fraud_platform_audit_evidence_export_repeated_fingerprint_total").increment();
    }

    public void recordReadAccessAuditPersisted(ReadAccessEndpointCategory endpointCategory, ReadAccessAuditOutcome outcome) {
        counter(
                "fraud_platform_read_access_audit_events_persisted_total",
                "endpoint_category", normalize(endpointCategory),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory endpointCategory) {
        counter(
                "fraud_platform_read_access_audit_persistence_failures_total",
                "endpoint_category", normalize(endpointCategory)
        ).increment();
    }

    public void recordReadAccessAuditActorMissing(ReadAccessEndpointCategory endpointCategory) {
        counter(
                "fraud_read_access_audit_actor_missing_total",
                "endpoint_category", normalize(endpointCategory)
        ).increment();
    }

    public void recordAuthenticationFailure(HttpServletRequest request, AuthenticationException exception) {
        counter(
                "fraud.security.auth.failures",
                "auth_type", SecurityFailureClassifier.authType(request),
                "endpoint", endpoint(request),
                "reason", SecurityFailureClassifier.authenticationFailureReason(request, exception)
        ).increment();
    }

    public void recordAccessDenied(HttpServletRequest request, Authentication authentication) {
        counter(
                "fraud.security.access.denied",
                "auth_type", SecurityFailureClassifier.authType(request),
                "endpoint", endpoint(request),
                "reason", SecurityFailureClassifier.accessDeniedReason(authentication),
                "actor_type", SecurityFailureClassifier.actorType(authentication)
        ).increment();
    }

    public void recordActorMismatch(String action) {
        counter(
                "fraud.security.actor.mismatches",
                "action", normalizeAction(action)
        ).increment();
    }

    public void recordGovernanceAdvisoryLifecycle(String lifecycleStatus, String modelName, String modelVersion) {
        counter(
                "fraud_ml_governance_advisory_lifecycle_total",
                "lifecycle_status", normalizeLabel(lifecycleStatus),
                "model_name", normalizeLabel(modelName),
                "model_version", normalizeLabel(modelVersion)
        ).increment();
    }

    public void recordGovernanceLifecycleStatus(String status) {
        counter(
                "lifecycle_status_total",
                "status", normalizeLifecycleStatus(status)
        ).increment();
    }

    public void recordGovernanceLifecycleDegraded(String reason) {
        counter(
                "lifecycle_degraded_total",
                "reason", normalizeLifecycleDegradationReason(reason)
        ).increment();
    }

    public void recordGovernanceAnalyticsRequest(int windowDays) {
        governanceAnalyticsWindowDays.set(windowDays);
        counter("fraud_ml_governance_analytics_requests_total").increment();
    }

    public void recordGovernanceAnalyticsOutcome(String status, Duration latency) {
        counter(
                "fraud_ml_governance_analytics_status_total",
                "status", normalizeAnalyticsStatus(status)
        ).increment();
        Timer.builder("fraud_ml_governance_analytics_latency_seconds")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(latency);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    private String endpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method)
                && path != null
                && path.startsWith("/api/v1/alerts/")
                && path.endsWith("/decision")) {
            return "alerts_decision";
        }
        if (path == null) {
            return "unknown";
        }
        if (path.startsWith("/api/v1/alerts")) {
            return "alerts";
        }
        if (path.startsWith("/api/v1/fraud-cases")) {
            return "fraud_cases";
        }
        if (path.startsWith("/api/v1/transactions/scored")) {
            return "scored_transactions";
        }
        if (path.startsWith("/actuator")) {
            return "actuator";
        }
        return "unknown";
    }

    private String normalize(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private String normalizeAction(String action) {
        if (!StringUtils.hasText(action)) {
            return "unknown";
        }
        return action.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    private String normalizeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:-]+", "_");
    }

    private String normalizeAnalyticsStatus(String status) {
        if ("AVAILABLE".equals(status) || "PARTIAL".equals(status) || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }

    private String normalizeLifecycleStatus(String status) {
        if ("OPEN".equals(status) || "RESOLVED".equals(status) || "UNKNOWN".equals(status)) {
            return status;
        }
        if ("ACKNOWLEDGED".equals(status) || "NEEDS_FOLLOW_UP".equals(status) || "DISMISSED_AS_NOISE".equals(status)) {
            return "RESOLVED";
        }
        return "UNKNOWN";
    }

    private String normalizeLifecycleDegradationReason(String reason) {
        if ("AUDIT_UNAVAILABLE".equals(reason)) {
            return reason;
        }
        return "AUDIT_UNAVAILABLE";
    }

    private String normalizeAvailabilityStatus(String status) {
        if ("AVAILABLE".equals(status) || "PARTIAL".equals(status) || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }

    private String normalizeIntegrityStatus(String status) {
        if ("VALID".equals(status) || "INVALID".equals(status) || "PARTIAL".equals(status) || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }

    private String normalizeIntegrityViolationType(String violationType) {
        return switch (violationType) {
            case "EVENT_HASH_MISMATCH",
                 "PREVIOUS_HASH_MISMATCH",
                 "INVALID_SCHEMA_VERSION",
                 "UNSUPPORTED_HASH_ALGORITHM",
                 "ANCHOR_MISSING",
                 "ANCHOR_HASH_MISMATCH",
                 "ANCHOR_CHAIN_POSITION_MISMATCH",
                 "MISSING_PREDECESSOR",
                 "CHAIN_FORK_DETECTED",
                 "CHAIN_POSITION_INVALID",
                 "CHAIN_POSITION_DUPLICATE",
                 "CHAIN_POSITION_GAP",
                 "EXTERNAL_ANCHOR_MISSING",
                 "STALE_EXTERNAL_ANCHOR",
                 "EXTERNAL_CHAIN_POSITION_AHEAD",
                 "EXTERNAL_HASH_MISMATCH",
                 "EXTERNAL_PAYLOAD_HASH_MISMATCH",
                 "EXTERNAL_OBJECT_KEY_MISMATCH",
                 "EXTERNAL_CHAIN_POSITION_MISMATCH",
                 "EXTERNAL_HASH_ALGORITHM_MISMATCH",
                 "EXTERNAL_SCHEMA_VERSION_UNSUPPORTED",
                 "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH" -> violationType;
            default -> "UNKNOWN";
        };
    }

    private String normalizeExternalSink(String sink) {
        if ("local-file".equals(sink) || "object-store".equals(sink) || "disabled".equals(sink)) {
            return sink;
        }
        return "unknown";
    }

    private String normalizeExternalAnchorPublishStatus(String status) {
        if ("PUBLISHED".equals(status) || "DUPLICATE".equals(status)) {
            return status;
        }
        return "FAILED";
    }

    private String normalizeExternalAnchorFailureReason(String reason) {
        return switch (reason) {
            case "DISABLED", "UNAVAILABLE", "CONFLICT", "MISMATCH", "IO_ERROR", "INVALID_ANCHOR",
                 "WRITE_NOT_VERIFIED", "EXTERNAL_PAYLOAD_HASH_MISMATCH", "EXTERNAL_OBJECT_KEY_MISMATCH", "TIMEOUT" -> reason;
            default -> "UNKNOWN";
        };
    }

    private String normalizeExternalAnchorOperation(String operation) {
        return switch (operation) {
            case "get", "put", "list", "immutability" -> operation;
            default -> "unknown";
        };
    }

    private long hashFingerprint(String hash) {
        if (hash == null || hash.length() < 12) {
            return 0L;
        }
        try {
            byte[] bytes = HexFormat.of().parseHex(hash.substring(0, 12));
            long value = 0L;
            for (byte current : bytes) {
                value = (value << 8) | (current & 0xffL);
            }
            return value;
        } catch (IllegalArgumentException exception) {
            return 0L;
        }
    }
}
