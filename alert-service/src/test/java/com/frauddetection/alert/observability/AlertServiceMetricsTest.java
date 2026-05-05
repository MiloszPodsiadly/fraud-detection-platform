package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.regulated.RegulatedMutationLeaseRenewalReason;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationState;
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

    @Test
    void shouldUseLowCardinalityRegulatedMutationFencingMetricLabels() {
        metrics.recordRegulatedMutationFencedTransition(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                "SUCCESS",
                "NONE"
        );
        metrics.recordRegulatedMutationStaleWriteRejected(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                "EXPIRED_LEASE"
        );
        metrics.recordRegulatedMutationLeaseTakeover(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED
        );
        metrics.recordRegulatedMutationLeaseRemainingAtTransition(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                "SUCCESS",
                Duration.ofSeconds(3)
        );
        metrics.recordRegulatedMutationTransitionLatency(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                "SUCCESS",
                Duration.ofMillis(3)
        );
        metrics.recordRegulatedMutationRecoveryWriteConflict(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                "RECOVERY_WRITE_CONFLICT"
        );
        metrics.recordRegulatedMutationLeaseBudgetWarning(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                "LOW_REMAINING"
        );
        metrics.recordRegulatedMutationLeaseRenewal(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                "SUCCESS",
                "NONE"
        );
        metrics.recordRegulatedMutationLeaseRenewal(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                "REJECTED",
                "raw alert-123 actor-456 exception"
        );
        metrics.recordRegulatedMutationLeaseRenewalBudgetRemaining(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                Duration.ofSeconds(30)
        );
        metrics.recordRegulatedMutationLeaseRenewalExtension(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                "SUCCESS",
                Duration.ofSeconds(5)
        );
        metrics.recordRegulatedMutationLeaseRenewalRejected(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                "raw alert-123 actor-456 exception"
        );
        for (RegulatedMutationLeaseRenewalReason reason : RegulatedMutationLeaseRenewalReason.values()) {
            metrics.recordRegulatedMutationLeaseRenewalRejected(
                    RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                    RegulatedMutationState.REQUESTED,
                    reason.name()
            );
        }
        metrics.recordRegulatedMutationLeaseRenewalBudgetExceeded(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED
        );
        metrics.recordRegulatedMutationLeaseRenewalSingleExtensionCapped(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED
        );
        metrics.recordRegulatedMutationLeaseRenewalTotalBudgetCapped(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED
        );

        Meter fencedTransition = meterRegistry.get("regulated_mutation_fenced_transition_total").meter();
        Meter staleWrite = meterRegistry.get("regulated_mutation_stale_write_rejected_total").meter();
        Meter takeover = meterRegistry.get("regulated_mutation_lease_takeover_total").meter();
        Meter leaseRemaining = meterRegistry.get("regulated_mutation_lease_remaining_at_transition_seconds").meter();
        Meter transitionLatency = meterRegistry.get("regulated_mutation_transition_latency_seconds").meter();
        Meter recoveryConflict = meterRegistry.get("regulated_mutation_recovery_write_conflict_total").meter();
        Meter leaseBudgetWarning = meterRegistry.get("regulated_mutation_lease_budget_warning_total").meter();
        Meter renewal = meterRegistry.get("regulated_mutation_lease_renewal_total")
                .tag("outcome", "SUCCESS")
                .meter();
        Meter renewalUnknownReason = meterRegistry.get("regulated_mutation_lease_renewal_total")
                .tag("outcome", "REJECTED")
                .tag("reason", "UNKNOWN")
                .meter();
        Meter renewalBudgetRemaining = meterRegistry.get("regulated_mutation_lease_renewal_budget_remaining_seconds").meter();
        Meter renewalExtension = meterRegistry.get("regulated_mutation_lease_renewal_extension_seconds").meter();
        Meter renewalRejected = meterRegistry.get("regulated_mutation_lease_renewal_rejected_total").meter();
        Meter renewalInvalidExtension = meterRegistry.get("regulated_mutation_lease_renewal_rejected_total")
                .tag("reason", "INVALID_EXTENSION")
                .meter();
        Meter renewalCommandNotFound = meterRegistry.get("regulated_mutation_lease_renewal_rejected_total")
                .tag("reason", "COMMAND_NOT_FOUND")
                .meter();
        Meter renewalUnknownRejected = meterRegistry.get("regulated_mutation_lease_renewal_rejected_total")
                .tag("reason", "UNKNOWN")
                .meter();
        Meter renewalBudgetExceeded = meterRegistry.get("regulated_mutation_lease_renewal_budget_exceeded_total").meter();
        Meter singleExtensionCapped = meterRegistry.get("regulated_mutation_lease_renewal_single_extension_capped_total").meter();
        Meter totalBudgetCapped = meterRegistry.get("regulated_mutation_lease_renewal_total_budget_capped_total").meter();

        assertThat(fencedTransition.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "outcome", "reason");
        assertThat(staleWrite.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "reason");
        assertThat(takeover.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state");
        assertThat(leaseRemaining.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "outcome");
        assertThat(transitionLatency.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "outcome");
        assertThat(recoveryConflict.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "reason");
        assertThat(leaseBudgetWarning.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "threshold");
        assertThat(renewal.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "outcome", "reason");
        assertThat(renewalUnknownReason.getId().getTags())
                .extracting(Tag::getValue)
                .contains("UNKNOWN");
        assertThat(renewalBudgetRemaining.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state");
        assertThat(renewalExtension.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "outcome");
        assertThat(renewalRejected.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state", "reason");
        assertThat(renewalInvalidExtension.getId().getTags())
                .extracting(Tag::getValue)
                .contains("INVALID_EXTENSION");
        assertThat(renewalCommandNotFound.getId().getTags())
                .extracting(Tag::getValue)
                .contains("COMMAND_NOT_FOUND");
        assertThat(renewalUnknownRejected.getId().getTags())
                .extracting(Tag::getValue)
                .contains("UNKNOWN");
        assertThat(renewalBudgetExceeded.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state");
        assertThat(singleExtensionCapped.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state");
        assertThat(totalBudgetCapped.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("model_version", "state");

        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("command_id")
                        .doesNotContain("alert_id")
                        .doesNotContain("actor_id")
                        .doesNotContain("lease_owner")
                        .doesNotContain("idempotency_key")
                        .doesNotContain("request_hash")
                        .doesNotContain("alert-123")
                        .doesNotContain("actor-456")
                        .doesNotContain("exception")
                        .doesNotContain("path"));
    }
}
