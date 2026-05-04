package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
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
    private final AtomicLong postCommitAuditDegraded = new AtomicLong(0);
    private final AtomicLong regulatedMutationRecoveryRequired = new AtomicLong(0);
    private final AtomicLong regulatedMutationRecoveryOldestAgeSeconds = new AtomicLong(0);
    private final AtomicLong regulatedMutationRecoveryFailedTerminal = new AtomicLong(0);
    private final AtomicLong regulatedMutationRecoveryRepeatedFailures = new AtomicLong(0);
    private final AtomicLong outboxPending = new AtomicLong(0);
    private final AtomicLong outboxProcessing = new AtomicLong(0);
    private final AtomicLong outboxConfirmationUnknown = new AtomicLong(0);
    private final AtomicLong outboxFailedTerminal = new AtomicLong(0);
    private final AtomicLong outboxProjectionMismatch = new AtomicLong(0);
    private final AtomicLong outboxOldestPendingAgeSeconds = new AtomicLong(0);
    private final AtomicLong evidenceConfirmationPending = new AtomicLong(0);
    private final AtomicInteger evidenceGatedFinalizeSubmitDecisionEnabled = new AtomicInteger(0);

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
        Gauge.builder("regulated_mutation_recovery_required_total", regulatedMutationRecoveryRequired, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_required_count", regulatedMutationRecoveryRequired, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_oldest_age_seconds", regulatedMutationRecoveryOldestAgeSeconds, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("oldest_recovery_required_age_seconds", regulatedMutationRecoveryOldestAgeSeconds, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_failed_terminal_count", regulatedMutationRecoveryFailedTerminal, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("recovery_failed_terminal_count", regulatedMutationRecoveryFailedTerminal, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_repeated_failures_total", regulatedMutationRecoveryRepeatedFailures, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("repeated_recovery_failures_count", regulatedMutationRecoveryRepeatedFailures, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("outbox_pending_count", outboxPending, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_processing_count", outboxProcessing, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_confirmation_unknown_count", outboxConfirmationUnknown, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_failed_terminal_count", outboxFailedTerminal, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_projection_mismatch_count", outboxProjectionMismatch, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_oldest_pending_age_seconds", outboxOldestPendingAgeSeconds, AtomicLong::get).register(meterRegistry);
        Gauge.builder("evidence_confirmation_pending_count", evidenceConfirmationPending, AtomicLong::get).register(meterRegistry);
        Gauge.builder("evidence_gated_finalize_enabled", evidenceGatedFinalizeSubmitDecisionEnabled, AtomicInteger::get)
                .tag("mutation_type", "SUBMIT_ANALYST_DECISION")
                .register(meterRegistry);
    }

    public void recordAnalystDecisionSubmitted() {
        counter("fraud.alert.decision.submissions", "outcome", "success").increment();
    }

    public void recordPostCommitAuditDegraded(String operation) {
        counter(
                "fraud_platform_post_commit_audit_degraded_total",
                "operation", normalizePostCommitOperation(operation)
        ).increment();
        postCommitAuditDegraded.incrementAndGet();
    }

    public long postCommitAuditDegradedCount() {
        return postCommitAuditDegraded.get();
    }

    public void recordRegulatedMutationRecoveryBacklog(long recoveryRequiredCount, Long oldestAgeSeconds) {
        regulatedMutationRecoveryRequired.set(Math.max(0L, recoveryRequiredCount));
        regulatedMutationRecoveryOldestAgeSeconds.set(oldestAgeSeconds == null ? 0L : Math.max(0L, oldestAgeSeconds));
    }

    public void recordRegulatedMutationRecoveryBacklog(
            long recoveryRequiredCount,
            Long oldestAgeSeconds,
            long failedTerminalCount,
            long repeatedFailureCount
    ) {
        recordRegulatedMutationRecoveryBacklog(recoveryRequiredCount, oldestAgeSeconds);
        regulatedMutationRecoveryFailedTerminal.set(Math.max(0L, failedTerminalCount));
        regulatedMutationRecoveryRepeatedFailures.set(Math.max(0L, repeatedFailureCount));
    }

    public void recordRegulatedMutationRecoveryOutcome(String outcome) {
        counter(
                "regulated_mutation_recovery_outcome_total",
                "outcome", normalizeRegulatedMutationRecoveryOutcome(outcome)
        ).increment();
    }

    public void recordDecisionOutboxPublishConfirmationFailed() {
        counter(
                "fraud_platform_decision_outbox_failures_total",
                "reason", "OUTBOX_PUBLISH_CONFIRMATION_FAILED"
        ).increment();
    }

    public void recordOutboxBacklog(OutboxBacklogResponse response) {
        outboxPending.set(Math.max(0L, response.pendingCount()));
        outboxProcessing.set(Math.max(0L, response.processingCount()));
        outboxConfirmationUnknown.set(Math.max(0L, response.confirmationUnknownCount()));
        outboxFailedTerminal.set(Math.max(0L, response.failedTerminalCount()));
        outboxProjectionMismatch.set(Math.max(0L, response.projectionMismatchCount()));
        outboxOldestPendingAgeSeconds.set(response.oldestPendingAgeSeconds() == null ? 0L : Math.max(0L, response.oldestPendingAgeSeconds()));
    }

    public void recordOutboxProjectionMismatch(long mismatchCount) {
        outboxProjectionMismatch.set(Math.max(0L, mismatchCount));
        counter("outbox_projection_mismatch_total", "reason", "PROJECTION_UPDATE_FAILED").increment();
    }

    public void recordOutboxPublishAttempt(String result) {
        counter(
                "outbox_publish_attempt_total",
                "result", normalizeOutboxPublishResult(result)
        ).increment();
    }

    public void recordOutboxDeliveryLatency(Duration latency) {
        Timer.builder("outbox_delivery_latency_seconds")
                .register(meterRegistry)
                .record(latency == null || latency.isNegative() ? Duration.ZERO : latency);
    }

    public void recordEvidenceConfirmationPending(long pendingCount) {
        evidenceConfirmationPending.set(Math.max(0L, pendingCount));
    }

    public void recordEvidenceConfirmationFailed(String reason) {
        counter(
                "evidence_confirmation_failed_total",
                "reason", normalizeEvidenceConfirmationFailure(reason)
        ).increment();
    }

    public void recordEvidenceGatedFinalizeStateTransition(Enum<?> from, Enum<?> to, String outcome) {
        counter(
                "evidence_gated_finalize_state_transition_total",
                "from", normalizeEvidenceGatedFinalizeState(from),
                "to", normalizeEvidenceGatedFinalizeState(to),
                "outcome", normalizeEvidenceGatedFinalizeOutcome(outcome)
        ).increment();
    }

    public void recordEvidenceGatedFinalizeRecoveryRequired(String reason) {
        counter(
                "evidence_gated_finalize_recovery_required_total",
                "reason", normalizeEvidenceGatedFinalizeReason(reason)
        ).increment();
    }

    public void recordEvidenceGatedFinalizeRejected(String reason) {
        counter(
                "evidence_gated_finalize_rejected_total",
                "reason", normalizeEvidenceGatedFinalizeReason(reason)
        ).increment();
    }

    public void recordEvidenceGatedFinalizeTransactionRollback(String reason) {
        counter(
                "evidence_gated_finalize_transaction_rollback_total",
                "reason", normalizeEvidenceGatedFinalizeReason(reason)
        ).increment();
    }

    public void recordEvidenceGatedFinalizeStuckVisible() {
        counter("evidence_gated_finalize_stuck_visible_total").increment();
    }

    public void recordEvidenceGatedFinalizeEnabled(String mutationType, boolean enabled) {
        if ("SUBMIT_ANALYST_DECISION".equals(mutationType)) {
            evidenceGatedFinalizeSubmitDecisionEnabled.set(enabled ? 1 : 0);
        }
    }

    public void recordRegulatedMutationFencedTransition(
            Enum<?> modelVersion,
            Enum<?> fromState,
            Enum<?> toState,
            String outcome,
            String reason
    ) {
        counter(
                "regulated_mutation_fenced_transition_total",
                "model_version", normalizeRegulatedMutationModelVersion(modelVersion),
                "state", normalizeRegulatedMutationState(fromState),
                "outcome", normalizeRegulatedMutationFencingOutcome(outcome),
                "reason", normalizeRegulatedMutationFencingReason(reason)
        ).increment();
    }

    public void recordRegulatedMutationLeaseRemainingAtTransition(
            Enum<?> modelVersion,
            Enum<?> state,
            String outcome,
            Duration leaseRemaining
    ) {
        Timer.builder("regulated_mutation_lease_remaining_at_transition_seconds")
                .tag("model_version", normalizeRegulatedMutationModelVersion(modelVersion))
                .tag("state", normalizeRegulatedMutationState(state))
                .tag("outcome", normalizeRegulatedMutationFencingOutcome(outcome))
                .register(meterRegistry)
                .record(leaseRemaining == null || leaseRemaining.isNegative() ? Duration.ZERO : leaseRemaining);
    }

    public void recordRegulatedMutationTransitionLatency(
            Enum<?> modelVersion,
            Enum<?> state,
            String outcome,
            Duration latency
    ) {
        Timer.builder("regulated_mutation_transition_latency_seconds")
                .tag("model_version", normalizeRegulatedMutationModelVersion(modelVersion))
                .tag("state", normalizeRegulatedMutationState(state))
                .tag("outcome", normalizeRegulatedMutationFencingOutcome(outcome))
                .register(meterRegistry)
                .record(latency == null || latency.isNegative() ? Duration.ZERO : latency);
    }

    public void recordRegulatedMutationStaleWriteRejected(Enum<?> modelVersion, Enum<?> state, String reason) {
        counter(
                "regulated_mutation_stale_write_rejected_total",
                "model_version", normalizeRegulatedMutationModelVersion(modelVersion),
                "state", normalizeRegulatedMutationState(state),
                "reason", normalizeRegulatedMutationFencingReason(reason)
        ).increment();
    }

    public void recordRegulatedMutationLeaseTakeover(Enum<?> modelVersion, Enum<?> state) {
        counter(
                "regulated_mutation_lease_takeover_total",
                "model_version", normalizeRegulatedMutationModelVersion(modelVersion),
                "state", normalizeRegulatedMutationState(state)
        ).increment();
    }

    public void recordRegulatedMutationRecoveryWriteConflict(Enum<?> modelVersion, Enum<?> state, String reason) {
        counter(
                "regulated_mutation_recovery_write_conflict_total",
                "model_version", normalizeRegulatedMutationModelVersion(modelVersion),
                "state", normalizeRegulatedMutationState(state),
                "reason", normalizeRegulatedMutationFencingReason(reason)
        ).increment();
    }

    public void recordRegulatedMutationLeaseBudgetWarning(Enum<?> modelVersion, Enum<?> state, String threshold) {
        counter(
                "regulated_mutation_lease_budget_warning_total",
                "model_version", normalizeRegulatedMutationModelVersion(modelVersion),
                "state", normalizeRegulatedMutationState(state),
                "threshold", normalizeRegulatedMutationLeaseBudgetThreshold(threshold)
        ).increment();
    }

    public void recordFdp29LocalAuditChainAppend(String outcome) {
        counter(
                "fdp29_local_audit_chain_append_total",
                "outcome", normalizeFdp29LocalAuditChainAppendOutcome(outcome)
        ).increment();
    }

    public void recordFdp29LocalAuditChainRetry(String reason) {
        counter(
                "fdp29_local_audit_chain_retry_total",
                "reason", normalizeFdp29LocalAuditChainRetryReason(reason)
        ).increment();
    }

    public void recordFdp29LocalAuditChainAppendDuration(Duration duration) {
        Timer.builder("fdp29_local_audit_chain_append_duration_ms")
                .register(meterRegistry)
                .record(duration == null || duration.isNegative() ? Duration.ZERO : duration);
    }

    public void recordFdp29LocalAuditChainLockReleaseFailure() {
        counter("fdp29_local_audit_chain_lock_release_failure_total").increment();
    }

    public void recordExternalCoverageRequestCost(String status, int cost) {
        DistributionSummary.builder("fraud_platform_audit_external_coverage_request_cost")
                .tag("status", normalizeCoverageRateLimitStatus(status))
                .register(meterRegistry)
                .record(Math.max(1, Math.min(cost, 100)));
    }

    public void recordFraudCaseUpdated() {
        counter("fraud.alert.fraud_case.updates", "outcome", "success").increment();
    }

    public void recordTrustIncidentMaterialized(String type, String severity, String result) {
        counter(
                "trust_incident_materialized_total",
                "type", normalizeTrustIncidentType(type),
                "severity", normalizeTrustIncidentSeverity(severity),
                "result", normalizeTrustIncidentMaterializationResult(result)
        ).increment();
    }

    public void recordTrustIncidentDeduped(String type, String severity) {
        counter(
                "trust_incident_deduped_total",
                "type", normalizeTrustIncidentType(type),
                "severity", normalizeTrustIncidentSeverity(severity)
        ).increment();
    }

    public void recordTrustIncidentMaterializationFailed(String reason) {
        counter(
                "trust_incident_materialization_failed_total",
                "reason", normalizeTrustIncidentMaterializationFailure(reason)
        ).increment();
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

    public void recordExternalAnchorStatusPersistenceFailed(String sink) {
        counter(
                "external_anchor_status_persistence_failed_total",
                "sink", normalizeExternalSink(sink)
        ).increment();
    }

    public void recordExternalAnchorRequiredFailedAfterLocalAnchor(String sink) {
        counter(
                "external_anchor_required_failed_after_local_anchor_total",
                "sink", normalizeExternalSink(sink)
        ).increment();
    }

    public void recordExternalAnchorStatusRecovered(String sink) {
        counter(
                "external_anchor_status_recovered_total",
                "sink", normalizeExternalSink(sink)
        ).increment();
    }

    public void recordExternalAnchorStatusRecoveryFailed(String sink, String reason) {
        counter(
                "external_anchor_status_recovery_failed_total",
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

    public void recordAuditSignatureVerification(String status) {
        counter(
                "audit_signature_verification_total",
                "status", normalizeSignatureVerificationStatus(status)
        ).increment();
    }

    public void recordAuditSignaturePolicyResult(String result) {
        counter(
                "audit_signature_policy_result_total",
                "result", normalizeSignaturePolicyResult(result)
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

    public void recordExternalManifestRead(String status) {
        counter(
                "external_manifest_read_total",
                "status", normalizeExternalManifestReadStatus(status)
        ).increment();
    }

    public void recordExternalManifestUpdate(String status) {
        counter(
                "external_manifest_update_total",
                "status", normalizeExternalManifestUpdateStatus(status)
        ).increment();
    }

    public void recordExternalManifestFallbackScan() {
        counter("external_manifest_fallback_scan_total").increment();
    }

    public void recordExternalManifestInvalid() {
        counter("external_manifest_invalid_total").increment();
    }

    public void recordExternalManifestMismatch() {
        counter("external_manifest_mismatch_total").increment();
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

    private String normalizePostCommitOperation(String operation) {
        if ("SUBMIT_ANALYST_DECISION".equals(operation)
                || "UPDATE_FRAUD_CASE".equals(operation)
                || "RESOLVE_DECISION_OUTBOX_CONFIRMATION".equals(operation)
                || "ACK_TRUST_INCIDENT".equals(operation)
                || "RESOLVE_TRUST_INCIDENT".equals(operation)) {
            return operation;
        }
        return "UNKNOWN";
    }

    private String normalizeTrustIncidentType(String type) {
        return switch (type) {
            case "OUTBOX_TERMINAL_FAILURE",
                 "OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN",
                 "OUTBOX_PROJECTION_MISMATCH",
                 "REGULATED_MUTATION_RECOVERY_REQUIRED",
                 "REGULATED_MUTATION_COMMITTED_DEGRADED",
                 "EVIDENCE_CONFIRMATION_FAILED",
                 "AUDIT_DEGRADATION_UNRESOLVED",
                 "COVERAGE_UNAVAILABLE",
                 "EXTERNAL_ANCHOR_GAP",
                 "TRUST_AUTHORITY_UNAVAILABLE" -> type;
            default -> "UNKNOWN";
        };
    }

    private String normalizeTrustIncidentSeverity(String severity) {
        return switch (severity) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW" -> severity;
            default -> "MEDIUM";
        };
    }

    private String normalizeTrustIncidentMaterializationResult(String result) {
        return switch (result) {
            case "CREATED", "UPDATED" -> result;
            default -> "UPDATED";
        };
    }

    private String normalizeTrustIncidentMaterializationFailure(String reason) {
        return switch (reason) {
            case "PERSISTENCE_UNAVAILABLE", "AUDIT_UNAVAILABLE" -> reason;
            default -> "PERSISTENCE_UNAVAILABLE";
        };
    }

    private String normalizeRegulatedMutationRecoveryOutcome(String outcome) {
        return switch (outcome) {
            case "RECOVERED", "STILL_PENDING", "RECOVERY_REQUIRED", "FAILED_TERMINAL" -> outcome;
            default -> "RECOVERY_REQUIRED";
        };
    }

    private String normalizeOutboxPublishResult(String result) {
        return switch (result) {
            case "SUCCESS", "FAILED", "CONFIRMATION_UNKNOWN" -> result;
            default -> "FAILED";
        };
    }

    private String normalizeEvidenceConfirmationFailure(String reason) {
        return switch (reason) {
            case "OUTBOX_NOT_YET_PUBLISHED", "OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT", "SUCCESS_AUDIT_MISSING",
                 "EXTERNAL_ANCHOR_MISSING", "SIGNATURE_UNAVAILABLE", "OUTBOX_FAILED_TERMINAL", "SIGNATURE_INVALID" -> reason;
            default -> "UNKNOWN";
        };
    }

    private String normalizeEvidenceGatedFinalizeState(Enum<?> state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state.name()) {
            case "REQUESTED", "EVIDENCE_PREPARING", "EVIDENCE_PREPARED", "FINALIZING",
                 "FINALIZED_VISIBLE", "FINALIZED_EVIDENCE_PENDING_EXTERNAL", "FINALIZED_EVIDENCE_CONFIRMED",
                 "REJECTED_EVIDENCE_UNAVAILABLE", "FAILED_BUSINESS_VALIDATION", "FINALIZE_RECOVERY_REQUIRED" -> state.name();
            default -> "UNKNOWN";
        };
    }

    private String normalizeEvidenceGatedFinalizeOutcome(String outcome) {
        return switch (outcome) {
            case "SUCCESS", "FAILED", "REJECTED", "RECOVERY_REQUIRED" -> outcome;
            default -> "FAILED";
        };
    }

    private String normalizeEvidenceGatedFinalizeReason(String reason) {
        return switch (reason) {
            case "ATTEMPTED_AUDIT_UNAVAILABLE", "EVIDENCE_GATED_TRANSACTION_REQUIRED",
                 "EVIDENCE_GATED_FINALIZE_FAILED", "FINALIZING_RETRY_REQUIRES_RECONCILIATION",
                 "FINALIZED_VISIBLE_MISSING_PROOF", "SUCCESS_AUDIT_MISSING", "OUTBOX_FAILED_TERMINAL",
                 "OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT", "OUTBOX_NOT_YET_PUBLISHED",
                 "SIGNATURE_INVALID", "BUSINESS_VALIDATION_FAILED", "TRANSACTION_CAPABILITY_UNAVAILABLE",
                 "OUTBOX_REPOSITORY_UNAVAILABLE", "OUTBOX_RECOVERY_DISABLED", "RECOVERY_STRATEGY_UNAVAILABLE",
                 "ACTOR_INTENT_MISMATCH", "RESOURCE_INTENT_MISMATCH", "ACTION_INTENT_MISMATCH",
                 "SUCCESS_AUDIT_KEY_UNAVAILABLE" -> reason;
            default -> "UNKNOWN";
        };
    }

    private String normalizeRegulatedMutationModelVersion(Enum<?> modelVersion) {
        if (modelVersion == null) {
            return "UNKNOWN";
        }
        return switch (modelVersion.name()) {
            case "LEGACY_REGULATED_MUTATION", "EVIDENCE_GATED_FINALIZE_V1" -> modelVersion.name();
            default -> "UNKNOWN";
        };
    }

    private String normalizeRegulatedMutationState(Enum<?> state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state.name()) {
            case "REQUESTED", "EVIDENCE_PREPARING", "EVIDENCE_PREPARED", "FINALIZING",
                 "FINALIZED_VISIBLE", "FINALIZED_EVIDENCE_PENDING_EXTERNAL", "FINALIZED_EVIDENCE_CONFIRMED",
                 "REJECTED_EVIDENCE_UNAVAILABLE", "FAILED_BUSINESS_VALIDATION", "FINALIZE_RECOVERY_REQUIRED",
                 "AUDIT_ATTEMPTED", "BUSINESS_COMMITTING", "BUSINESS_COMMITTED", "SUCCESS_AUDIT_PENDING",
                 "SUCCESS_AUDIT_RECORDED", "EVIDENCE_PENDING", "EVIDENCE_CONFIRMED", "COMMITTED",
                 "COMMITTED_DEGRADED", "REJECTED", "FAILED" -> state.name();
            default -> "UNKNOWN";
        };
    }

    private String normalizeRegulatedMutationFencingOutcome(String outcome) {
        return switch (outcome) {
            case "SUCCESS", "REJECTED" -> outcome;
            default -> "REJECTED";
        };
    }

    private String normalizeRegulatedMutationFencingReason(String reason) {
        return switch (reason) {
            case "NONE", "STALE_LEASE_OWNER", "EXPIRED_LEASE", "EXPECTED_STATE_MISMATCH",
                 "EXPECTED_STATUS_MISMATCH", "COMMAND_NOT_FOUND", "RECOVERY_WRITE_CONFLICT", "UNKNOWN" -> reason;
            default -> "UNKNOWN";
        };
    }

    private String normalizeRegulatedMutationLeaseBudgetThreshold(String threshold) {
        return switch (threshold) {
            case "LOW_REMAINING" -> threshold;
            default -> "LOW_REMAINING";
        };
    }

    private String normalizeFdp29LocalAuditChainAppendOutcome(String outcome) {
        return switch (outcome) {
            case "SUCCESS", "DUPLICATE_PHASE", "CHAIN_CONFLICT_RETRY", "CHAIN_CONFLICT_EXHAUSTED",
                 "AUDIT_INSERT_FAILED", "ANCHOR_INSERT_FAILED", "LOCK_RELEASE_FAILED" -> outcome;
            default -> "CHAIN_CONFLICT_EXHAUSTED";
        };
    }

    private String normalizeFdp29LocalAuditChainRetryReason(String reason) {
        return switch (reason) {
            case "CHAIN_CONFLICT", "DUPLICATE_KEY", "LOCK_CONFLICT" -> reason;
            default -> "CHAIN_CONFLICT";
        };
    }

    private String normalizeCoverageRateLimitStatus(String status) {
        if ("ALLOWED".equals(status) || "RATE_LIMITED".equals(status)) {
            return status;
        }
        return "RATE_LIMITED";
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

    private String normalizeSignatureVerificationStatus(String status) {
        return switch (status) {
            case "VALID", "INVALID", "UNSIGNED", "UNAVAILABLE", "UNKNOWN_KEY", "KEY_REVOKED" -> status;
            default -> "INVALID";
        };
    }

    private String normalizeSignaturePolicyResult(String result) {
        return switch (result) {
            case "VALID", "PARTIAL", "INVALID" -> result;
            default -> "INVALID";
        };
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
                 "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH",
                 "SIGNATURE_UNSIGNED",
                 "SIGNATURE_UNSIGNED_REQUIRED",
                 "SIGNATURE_UNAVAILABLE",
                 "SIGNATURE_UNAVAILABLE_REQUIRED",
                 "SIGNATURE_INVALID",
                 "SIGNATURE_UNKNOWN_KEY",
                 "SIGNATURE_KEY_REVOKED" -> violationType;
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
        if ("PUBLISHED".equals(status)
                || "DUPLICATE".equals(status)
                || "UNVERIFIED".equals(status)
                || "LOCAL_STATUS_UNVERIFIED".equals(status)
                || "LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED".equals(status)
                || "PARTIAL".equals(status)
                || "INVALID".equals(status)
                || "CONFLICT".equals(status)
                || "FAILED".equals(status)) {
            if ("PARTIAL".equals(status)) {
                return "UNVERIFIED";
            }
            return status;
        }
        return "FAILED";
    }

    private String normalizeExternalManifestReadStatus(String status) {
        return switch (status) {
            case "HIT", "MISS", "INVALID" -> status;
            default -> "INVALID";
        };
    }

    private String normalizeExternalManifestUpdateStatus(String status) {
        return switch (status) {
            case "SUCCESS", "FAILED" -> status;
            default -> "FAILED";
        };
    }

    private String normalizeExternalAnchorFailureReason(String reason) {
        return switch (reason) {
            case "DISABLED", "UNAVAILABLE", "CONFLICT", "MISMATCH", "IO_ERROR", "INVALID_ANCHOR",
                 "WRITE_NOT_VERIFIED", "EXTERNAL_PAYLOAD_HASH_MISMATCH", "EXTERNAL_OBJECT_KEY_MISMATCH", "TIMEOUT",
                 "EXTERNAL_ANCHOR_ID_MISMATCH", "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED",
                 "HEAD_SCAN_PAGINATION_UNSUPPORTED", "HEAD_SCAN_LIMIT_EXCEEDED", "HEAD_MANIFEST_INVALID",
                 "HEAD_MANIFEST_UPDATE_FAILED", "SIGNATURE_FAILED",
                 "STATUS_PERSISTENCE_FAILED_AFTER_EXTERNAL_PUBLISH", "EXTERNAL_ANCHOR_REQUIRED_FAILED",
                 "MISSING", "INVALID" -> reason;
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
