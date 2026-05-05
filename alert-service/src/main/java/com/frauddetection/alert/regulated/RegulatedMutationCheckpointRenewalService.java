package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class RegulatedMutationCheckpointRenewalService {

    private final RegulatedMutationSafeCheckpointPolicy checkpointPolicy;
    private final RegulatedMutationLeaseRenewalService leaseRenewalService;
    private final AlertServiceMetrics metrics;
    private final Duration requestedExtension;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public RegulatedMutationCheckpointRenewalService(
            RegulatedMutationSafeCheckpointPolicy checkpointPolicy,
            RegulatedMutationLeaseRenewalService leaseRenewalService,
            AlertServiceMetrics metrics,
            @Value("${app.regulated-mutations.checkpoint-renewal.extension:PT30S}") Duration requestedExtension
    ) {
        this(checkpointPolicy, leaseRenewalService, metrics, requestedExtension, Clock.systemUTC(), true);
    }

    RegulatedMutationCheckpointRenewalService(
            RegulatedMutationSafeCheckpointPolicy checkpointPolicy,
            RegulatedMutationLeaseRenewalService leaseRenewalService,
            AlertServiceMetrics metrics,
            Duration requestedExtension,
            Clock clock
    ) {
        this(checkpointPolicy, leaseRenewalService, metrics, requestedExtension, clock, true);
    }

    private RegulatedMutationCheckpointRenewalService(
            RegulatedMutationSafeCheckpointPolicy checkpointPolicy,
            RegulatedMutationLeaseRenewalService leaseRenewalService,
            AlertServiceMetrics metrics,
            Duration requestedExtension,
            Clock clock,
            boolean enabled
    ) {
        this.checkpointPolicy = checkpointPolicy;
        this.leaseRenewalService = leaseRenewalService;
        this.metrics = metrics;
        this.requestedExtension = requestedExtension == null ? Duration.ofSeconds(30) : requestedExtension;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.enabled = enabled;
    }

    static RegulatedMutationCheckpointRenewalService disabled() {
        return new RegulatedMutationCheckpointRenewalService(null, null, null, Duration.ZERO, Clock.systemUTC(), false);
    }

    public RegulatedMutationCheckpointRenewalDecision beforeAttemptedAudit(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.BEFORE_ATTEMPTED_AUDIT);
    }

    public RegulatedMutationCheckpointRenewalDecision afterAttemptedAudit(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.AFTER_ATTEMPTED_AUDIT);
    }

    public RegulatedMutationCheckpointRenewalDecision beforeLegacyBusinessCommit(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT);
    }

    public RegulatedMutationCheckpointRenewalDecision beforeSuccessAuditRetry(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY);
    }

    public RegulatedMutationCheckpointRenewalDecision beforeEvidencePreparation(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_PREPARATION);
    }

    public RegulatedMutationCheckpointRenewalDecision afterEvidencePreparedBeforeFinalize(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE);
    }

    public RegulatedMutationCheckpointRenewalDecision beforeEvidenceGatedFinalize(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document
    ) {
        return checkpoint(claimToken, document, RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE);
    }

    private RegulatedMutationCheckpointRenewalDecision checkpoint(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document,
            RegulatedMutationRenewalCheckpoint checkpoint
    ) {
        if (!enabled) {
            return RegulatedMutationCheckpointRenewalDecision.skipped(checkpoint);
        }
        if (claimToken == null) {
            throw new IllegalArgumentException("Regulated mutation checkpoint renewal requires claim token.");
        }
        if (document == null) {
            throw new IllegalArgumentException("Regulated mutation checkpoint renewal requires command document.");
        }
        Instant startedAt = clock.instant();
        RegulatedMutationModelVersion modelVersion = document.mutationModelVersionOrLegacy();
        RegulatedMutationLeaseRenewalReason policyReason = checkpointPolicy.rejectionReason(
                modelVersion,
                document.getState(),
                document.getExecutionStatus(),
                checkpoint
        );
        if (policyReason != RegulatedMutationLeaseRenewalReason.NONE) {
            recordBlocked(modelVersion, checkpoint, policyReason);
            recordDuration(modelVersion, checkpoint, startedAt);
            throw new RegulatedMutationCheckpointRenewalException(checkpoint, policyReason);
        }

        try {
            RegulatedMutationLeaseRenewalDecision renewal = leaseRenewalService.renew(claimToken, requestedExtension);
            document.setLeaseExpiresAt(renewal.newLeaseExpiresAt());
            recordRenewed(modelVersion, checkpoint, startedAt);
            metrics.recordRegulatedMutationCheckpointNoProgress(modelVersion, checkpoint, "NONE");
            return RegulatedMutationCheckpointRenewalDecision.renewed(checkpoint, renewal.newLeaseExpiresAt());
        } catch (RegulatedMutationLeaseRenewalBudgetExceededException exception) {
            recordFailed(modelVersion, checkpoint, RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED, startedAt);
            throw exception;
        } catch (StaleRegulatedMutationLeaseException exception) {
            RegulatedMutationLeaseRenewalReason reason = switch (exception.reason()) {
                case STALE_LEASE_OWNER -> RegulatedMutationLeaseRenewalReason.STALE_OWNER;
                case EXPIRED_LEASE -> RegulatedMutationLeaseRenewalReason.EXPIRED_LEASE;
                case EXPECTED_STATUS_MISMATCH -> RegulatedMutationLeaseRenewalReason.EXECUTION_STATUS_MISMATCH;
                case EXPECTED_STATE_MISMATCH -> RegulatedMutationLeaseRenewalReason.NON_RENEWABLE_STATE;
                default -> RegulatedMutationLeaseRenewalReason.UNKNOWN;
            };
            recordFailed(modelVersion, checkpoint, reason, startedAt);
            throw exception;
        } catch (RegulatedMutationLeaseRenewalException exception) {
            recordFailed(modelVersion, checkpoint, exception.reason(), startedAt);
            throw exception;
        }
    }

    private void recordRenewed(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationRenewalCheckpoint checkpoint,
            Instant startedAt
    ) {
        metrics.recordRegulatedMutationCheckpointRenewal(modelVersion, checkpoint, "RENEWED", "NONE");
        recordDuration(modelVersion, checkpoint, startedAt);
    }

    private void recordBlocked(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationRenewalCheckpoint checkpoint,
            RegulatedMutationLeaseRenewalReason reason
    ) {
        metrics.recordRegulatedMutationCheckpointRenewal(modelVersion, checkpoint, "BLOCKED", reason.name());
        metrics.recordRegulatedMutationCheckpointRenewalBlocked(modelVersion, checkpoint, reason.name());
        metrics.recordRegulatedMutationCheckpointNoProgress(modelVersion, checkpoint, reason.name());
    }

    private void recordFailed(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationRenewalCheckpoint checkpoint,
            RegulatedMutationLeaseRenewalReason reason,
            Instant startedAt
    ) {
        metrics.recordRegulatedMutationCheckpointRenewal(modelVersion, checkpoint, "FAILED", reason.name());
        metrics.recordRegulatedMutationCheckpointRenewalBlocked(modelVersion, checkpoint, reason.name());
        metrics.recordRegulatedMutationCheckpointNoProgress(modelVersion, checkpoint, reason.name());
        recordDuration(modelVersion, checkpoint, startedAt);
    }

    private void recordDuration(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationRenewalCheckpoint checkpoint,
            Instant startedAt
    ) {
        metrics.recordRegulatedMutationCheckpointDuration(modelVersion, checkpoint, Duration.between(startedAt, clock.instant()));
    }
}
