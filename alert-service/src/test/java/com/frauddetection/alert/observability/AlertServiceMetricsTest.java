package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void shouldUseOnlyBoundedLifecycleMetricLabels() {
        metrics.recordGovernanceAdvisoryLifecycle(
                "ACKNOWLEDGED",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1"
        );

        Meter meter = meterRegistry.get("fraud_ml_governance_advisory_lifecycle_total").meter();

        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("lifecycle_status", "model_name", "model_version");
        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("event_id", "actor_id", "audit_id", "endpoint", "reason", "exception");
    }

    @Test
    void shouldUseNoDynamicLabelsForAnalyticsMetrics() {
        metrics.recordGovernanceAnalyticsRequest(7);
        metrics.recordGovernanceAnalyticsOutcome("AVAILABLE", Duration.ofMillis(25));
        metrics.recordGovernanceLifecycleStatus("OPEN");
        metrics.recordGovernanceLifecycleStatus("ACKNOWLEDGED");
        metrics.recordGovernanceLifecycleDegraded("AUDIT_UNAVAILABLE");

        Meter requestMeter = meterRegistry.get("fraud_ml_governance_analytics_requests_total").meter();
        Meter windowMeter = meterRegistry.get("fraud_ml_governance_analytics_window_days").meter();
        Meter statusMeter = meterRegistry.get("fraud_ml_governance_analytics_status_total").meter();
        Meter latencyMeter = meterRegistry.get("fraud_ml_governance_analytics_latency_seconds").meter();
        Meter lifecycleStatusMeter = meterRegistry.get("lifecycle_status_total").tag("status", "OPEN").meter();
        Meter lifecycleResolvedMeter = meterRegistry.get("lifecycle_status_total").tag("status", "RESOLVED").meter();
        Meter lifecycleDegradedMeter = meterRegistry.get("lifecycle_degraded_total").meter();

        assertThat(requestMeter.getId().getTags()).isEmpty();
        assertThat(windowMeter.getId().getTags()).isEmpty();
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("AVAILABLE");
        assertThat(latencyMeter.getId().getTags()).isEmpty();
        assertThat(lifecycleStatusMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(lifecycleResolvedMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("RESOLVED");
        assertThat(lifecycleDegradedMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("reason");
        assertThat(lifecycleDegradedMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("AUDIT_UNAVAILABLE");
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_window_days").gauge().value()).isEqualTo(7.0);
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_latency_seconds").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_status_total").tag("status", "AVAILABLE").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldBoundAnalyticsStatusLabels() {
        metrics.recordGovernanceAnalyticsOutcome("event-123", Duration.ofMillis(1));

        Meter statusMeter = meterRegistry.get("fraud_ml_governance_analytics_status_total").meter();

        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("UNAVAILABLE");
    }

    @Test
    void shouldUseLowCardinalityPlatformAuditPersistenceMetricLabels() {
        metrics.recordPlatformAuditEventPersisted(AuditAction.SUBMIT_ANALYST_DECISION, AuditOutcome.SUCCESS);
        metrics.recordPlatformAuditPersistenceFailure(AuditAction.SUBMIT_ANALYST_DECISION);
        metrics.recordPlatformAuditReadRequest("AVAILABLE");
        metrics.recordAuditIntegrityCheck("INVALID");
        metrics.recordAuditIntegrityViolation("EVENT_HASH_MISMATCH");
        metrics.recordExternalAnchorPublished("local-file", "PUBLISHED");
        metrics.recordExternalAnchorPublishFailed("local-file", "IO_ERROR");
        metrics.recordExternalAnchorLag(Duration.ofSeconds(3));
        metrics.recordExternalManifestRead("HIT");
        metrics.recordExternalManifestUpdate("SUCCESS");
        metrics.recordExternalManifestFallbackScan();
        metrics.recordExternalManifestInvalid();
        metrics.recordExternalManifestMismatch();
        metrics.recordExternalIntegrityCheck("PARTIAL");
        metrics.recordEvidenceExport("PARTIAL");
        metrics.recordEvidenceExportRateLimited();
        metrics.recordEvidenceExportRepeatedFingerprint();
        metrics.recordPostCommitAuditDegraded("SUBMIT_ANALYST_DECISION");

        Meter persisted = meterRegistry.get("fraud_platform_audit_events_persisted_total").meter();
        Meter failures = meterRegistry.get("fraud_platform_audit_persistence_failures_total").meter();
        Meter reads = meterRegistry.get("fraud_platform_audit_read_requests_total").meter();
        Meter integrityChecks = meterRegistry.get("fraud_platform_audit_integrity_checks_total").meter();
        Meter integrityViolations = meterRegistry.get("fraud_platform_audit_integrity_violations_total").meter();
        Meter externalAnchorPublished = meterRegistry.get("fraud_platform_audit_external_anchor_published_total").meter();
        Meter externalAnchorFailures = meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total").meter();
        Meter externalAnchorLag = meterRegistry.get("fraud_platform_audit_external_anchor_lag_seconds").meter();
        Meter manifestRead = meterRegistry.get("external_manifest_read_total").meter();
        Meter manifestUpdate = meterRegistry.get("external_manifest_update_total").meter();
        Meter manifestFallback = meterRegistry.get("external_manifest_fallback_scan_total").meter();
        Meter manifestInvalid = meterRegistry.get("external_manifest_invalid_total").meter();
        Meter manifestMismatch = meterRegistry.get("external_manifest_mismatch_total").meter();
        Meter externalIntegrityChecks = meterRegistry.get("fraud_platform_audit_external_integrity_checks_total").meter();
        Meter evidenceExports = meterRegistry.get("fraud_platform_audit_evidence_exports_total").meter();
        Meter evidenceExportRateLimited = meterRegistry.get("fraud_platform_audit_evidence_export_rate_limited_total").meter();
        Meter evidenceExportRepeatedFingerprint = meterRegistry.get("fraud_platform_audit_evidence_export_repeated_fingerprint_total").meter();
        Meter postCommitDegraded = meterRegistry.get("fraud_platform_post_commit_audit_degraded_total").meter();

        assertThat(persisted.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("event_type", "outcome");
        assertThat(failures.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("event_type");
        assertThat(reads.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(integrityChecks.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(integrityViolations.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("violation_type");
        assertThat(externalAnchorPublished.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("sink", "status");
        assertThat(externalAnchorFailures.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("sink", "reason");
        assertThat(externalAnchorLag.getId().getTags()).isEmpty();
        assertThat(manifestRead.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(manifestRead.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("HIT");
        assertThat(manifestUpdate.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(manifestUpdate.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("SUCCESS");
        assertThat(manifestFallback.getId().getTags()).isEmpty();
        assertThat(manifestInvalid.getId().getTags()).isEmpty();
        assertThat(manifestMismatch.getId().getTags()).isEmpty();
        assertThat(externalIntegrityChecks.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(evidenceExports.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(evidenceExports.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("PARTIAL");
        assertThat(evidenceExportRateLimited.getId().getTags()).isEmpty();
        assertThat(evidenceExportRepeatedFingerprint.getId().getTags()).isEmpty();
        assertThat(postCommitDegraded.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("operation");
        assertThat(postCommitDegraded.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("SUBMIT_ANALYST_DECISION");
        assertThat(persisted.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("actor_id", "resource_id", "audit_event_id", "hash", "exception", "message");
        assertThat(externalAnchorPublished.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("actor_id", "resource_id", "audit_event_id", "hash", "path", "exception", "message");
    }

    @Test
    void shouldUseLowCardinalityEvidenceGatedFinalizeMetricLabels() {
        metrics.recordEvidenceGatedFinalizeStateTransition(
                com.frauddetection.alert.regulated.RegulatedMutationState.EVIDENCE_PREPARED,
                com.frauddetection.alert.regulated.RegulatedMutationState.FINALIZING,
                "SUCCESS"
        );
        metrics.recordEvidenceGatedFinalizeRecoveryRequired("OUTBOX_FAILED_TERMINAL");
        metrics.recordEvidenceGatedFinalizeRecoveryRequired("alert-123/raw-message");
        metrics.recordEvidenceGatedFinalizeRejected("ATTEMPTED_AUDIT_UNAVAILABLE");
        metrics.recordEvidenceGatedFinalizeTransactionRollback("EVIDENCE_GATED_FINALIZE_FAILED");
        metrics.recordEvidenceConfirmationFailed("idempotency-key-raw-value");
        metrics.recordEvidenceGatedFinalizeStuckVisible();
        metrics.recordEvidenceGatedFinalizeEnabled("SUBMIT_ANALYST_DECISION", true);
        metrics.recordFdp29LocalAuditChainAppend("SUCCESS");
        metrics.recordFdp29LocalAuditChainAppend("raw-command-id");
        metrics.recordFdp29LocalAuditChainRetry("LOCK_CONFLICT");
        metrics.recordFdp29LocalAuditChainRetry("raw-lock-owner");
        metrics.recordFdp29LocalAuditChainAppendDuration(Duration.ofMillis(3));
        metrics.recordFdp29LocalAuditChainLockReleaseFailure();

        Meter transition = meterRegistry.get("evidence_gated_finalize_state_transition_total").meter();
        Meter recovery = meterRegistry.get("evidence_gated_finalize_recovery_required_total").meter();
        Meter rejected = meterRegistry.get("evidence_gated_finalize_rejected_total").meter();
        Meter rollback = meterRegistry.get("evidence_gated_finalize_transaction_rollback_total").meter();
        Meter stuck = meterRegistry.get("evidence_gated_finalize_stuck_visible_total").meter();
        Meter enabled = meterRegistry.get("evidence_gated_finalize_enabled").meter();
        Meter localAuditAppendSuccess = meterRegistry.get("fdp29_local_audit_chain_append_total")
                .tag("outcome", "SUCCESS")
                .meter();
        Meter localAuditAppendUnknown = meterRegistry.get("fdp29_local_audit_chain_append_total")
                .tag("outcome", "CHAIN_CONFLICT_EXHAUSTED")
                .meter();
        Meter localAuditRetry = meterRegistry.get("fdp29_local_audit_chain_retry_total")
                .tag("reason", "LOCK_CONFLICT")
                .meter();
        Meter localAuditAppendDuration = meterRegistry.get("fdp29_local_audit_chain_append_duration_ms").meter();
        Meter localAuditLockReleaseFailure = meterRegistry.get("fdp29_local_audit_chain_lock_release_failure_total").meter();

        assertThat(transition.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("from", "to", "outcome");
        assertThat(recovery.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("reason");
        assertThat(rejected.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("reason");
        assertThat(rollback.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("reason");
        assertThat(stuck.getId().getTags()).isEmpty();
        assertThat(enabled.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("mutation_type");
        assertThat(localAuditAppendSuccess.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("outcome");
        assertThat(localAuditAppendUnknown.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("CHAIN_CONFLICT_EXHAUSTED");
        assertThat(localAuditRetry.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("reason");
        assertThat(localAuditAppendDuration.getId().getTags()).isEmpty();
        assertThat(localAuditLockReleaseFailure.getId().getTags()).isEmpty();
        assertThat(transition.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("alert_id", "command_id", "actor_id", "idempotency_key", "exception", "path");
        assertThat(localAuditAppendSuccess.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("alert_id", "command_id", "actor_id", "idempotency_key", "exception", "path", "lock_owner");
        assertThat(localAuditRetry.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("alert_id", "command_id", "actor_id", "idempotency_key", "exception", "path", "lock_owner");
        assertThat(meterRegistry.get("evidence_gated_finalize_recovery_required_total")
                .tag("reason", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("evidence_confirmation_failed_total")
                .tag("reason", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("fdp29_local_audit_chain_retry_total")
                .tag("reason", "CHAIN_CONFLICT")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
