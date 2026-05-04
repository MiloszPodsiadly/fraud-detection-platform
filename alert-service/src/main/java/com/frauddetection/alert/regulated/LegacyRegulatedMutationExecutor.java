package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.RegulatedMutationPublicStatusProjection;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class LegacyRegulatedMutationExecutor implements RegulatedMutationExecutor {

    private static final Logger log = LoggerFactory.getLogger(LegacyRegulatedMutationExecutor.class);
    private static final String BUSINESS_WRITE_FAILED = "BUSINESS_WRITE_FAILED";
    private static final String POST_COMMIT_AUDIT_DEGRADED = "POST_COMMIT_AUDIT_DEGRADED";
    private static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";

    private final RegulatedMutationCommandRepository commandRepository;
    private final RegulatedMutationAuditPhaseService auditPhaseService;
    private final AuditDegradationService auditDegradationService;
    private final AlertServiceMetrics metrics;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final boolean bankModeFailClosed;
    private final RegulatedMutationClaimService claimService;
    private final RegulatedMutationConflictPolicy conflictPolicy;
    private final RegulatedMutationReplayResolver replayResolver;

    public LegacyRegulatedMutationExecutor(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration
    ) {
        this(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                auditDegradationService,
                metrics,
                transactionRunner,
                publicStatusMapper,
                bankModeFailClosed,
                new RegulatedMutationClaimService(mongoTemplate, leaseDuration),
                new RegulatedMutationConflictPolicy(),
                new RegulatedMutationReplayResolver(compatibilityReplayPolicyRegistry())
        );
    }

    @Autowired
    public LegacyRegulatedMutationExecutor(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            RegulatedMutationClaimService claimService,
            RegulatedMutationConflictPolicy conflictPolicy,
            RegulatedMutationReplayResolver replayResolver
    ) {
        this.commandRepository = commandRepository;
        this.auditPhaseService = auditPhaseService;
        this.auditDegradationService = auditDegradationService;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner;
        this.publicStatusMapper = publicStatusMapper;
        this.bankModeFailClosed = bankModeFailClosed;
        this.claimService = claimService;
        this.conflictPolicy = conflictPolicy;
        this.replayResolver = replayResolver;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        // Broad support is intentional only for LEGACY_REGULATED_MUTATION compatibility.
        // New model executors must use strict action/resource allowlists instead.
        return action != null && resourceType != null;
    }

    @Override
    public <R, S> RegulatedMutationResult<S> execute(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey,
            RegulatedMutationCommandDocument document
    ) {
        RegulatedMutationResult<S> terminalOrPartial = terminalOrPartialResponse(command, document);
        if (terminalOrPartial != null) {
            return terminalOrPartial;
        }

        document = claimService.claim(command, idempotencyKey).orElse(null);
        if (document == null) {
            return concurrentResponse(command, idempotencyKey);
        }

        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED) {
            return new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(LegacyRegulatedMutationReplayPolicy.legacyRecoveryState(document))
            );
        }
        if (document.getResponseSnapshot() != null && !LegacyRegulatedMutationReplayPolicy.needsSuccessAuditRetry(document)) {
            return replay(command, document);
        }

        if (document.getState() == RegulatedMutationState.SUCCESS_AUDIT_PENDING && document.getResponseSnapshot() != null) {
            return retrySuccessAuditOnly(command, document);
        }

        if (!isSafeToExecuteBusinessMutation(document)) {
            return markRecoveryRequired(command, document, LegacyRegulatedMutationReplayPolicy.legacyRecoveryState(document));
        }

        if (!document.isAttemptedAuditRecorded()) {
            writeAttemptedAudit(command, document);
        }
        RegulatedMutationCommandDocument claimedDocument = document;
        LocalCommit<R, S> localCommit = transactionRunner.runLocalCommit(() -> {
            R result = executeBusinessMutation(command, claimedDocument);
            S pendingResponse = command.responseMapper().response(result, RegulatedMutationState.EVIDENCE_PENDING);
            RegulatedMutationResponseSnapshot pendingSnapshot = command.responseSnapshotter().snapshot(pendingResponse);
            markBusinessCommitted(claimedDocument, pendingSnapshot);
            return new LocalCommit<>(result, pendingResponse, pendingSnapshot);
        });
        S finalResponse = writeSuccessAudit(command, document, localCommit.result(), localCommit.pendingSnapshot());
        if (finalResponse != null) {
            return new RegulatedMutationResult<>(document.getState(), finalResponse);
        }
        return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, localCommit.pendingResponse());
    }

    private <R, S> RegulatedMutationResult<S> terminalOrPartialResponse(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        RegulatedMutationReplayDecision decision = replayResolver.resolve(document, Instant.now());
        return switch (decision.type()) {
            case NONE -> null;
            case ACTIVE_IN_PROGRESS -> new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(decision.responseState())
            );
            case REPLAY_SNAPSHOT -> replay(command, document);
            case RECOVERY_REQUIRED_RESPONSE -> markRecoveryRequired(command, document, decision.responseState());
            default -> throw new IllegalStateException("Unsupported legacy replay decision: " + decision.type());
        };
    }

    private <R, S> RegulatedMutationResult<S> replay(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        S restored = command.responseRestorer().restore(document.getResponseSnapshot());
        return new RegulatedMutationResult<>(document.getState(), authoritativePublicStatus(restored, document));
    }

    @SuppressWarnings("unchecked")
    private <S> S authoritativePublicStatus(S restored, RegulatedMutationCommandDocument document) {
        if (restored instanceof RegulatedMutationPublicStatusProjection<?> response) {
            return (S) response.withPublicStatus(publicStatusMapper.submitDecisionStatus(document));
        }
        return restored;
    }

    private <R, S> RegulatedMutationResult<S> concurrentResponse(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        RegulatedMutationCommandDocument current = commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> conflictPolicy.existingOrConflict(existing, command))
                .orElseThrow(MissingIdempotencyKeyException::new);
        RegulatedMutationResult<S> terminalOrPartial = terminalOrPartialResponse(command, current);
        if (terminalOrPartial != null) {
            return terminalOrPartial;
        }
        return new RegulatedMutationResult<>(
                current.getState(),
                command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
        );
    }

    private <R, S> void writeAttemptedAudit(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.ATTEMPTED, null);
            document.setAttemptedAuditId(auditId);
            document.setAttemptedAuditRecorded(true);
            transition(document, RegulatedMutationState.AUDIT_ATTEMPTED, null);
        } catch (RuntimeException exception) {
            document.setDegradationReason(ATTEMPTED_AUDIT_UNAVAILABLE);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
            transition(document, RegulatedMutationState.REJECTED, ATTEMPTED_AUDIT_UNAVAILABLE);
            throw exception;
        }
    }

    private <R, S> R executeBusinessMutation(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        transition(document, RegulatedMutationState.BUSINESS_COMMITTING, null);
        try {
            R result = command.mutation().execute(new RegulatedMutationExecutionContext(document.getId()));
            transition(document, RegulatedMutationState.BUSINESS_COMMITTED, null);
            return result;
        } catch (RegulatedMutationPartialCommitException exception) {
            if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED) {
                auditFailure(command, document, exception, "TRUST_INCIDENT_REFRESH_ROLLED_BACK");
                document.setDegradationReason("TRUST_INCIDENT_REFRESH_ROLLED_BACK");
                document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
                transition(document, RegulatedMutationState.FAILED, "TRUST_INCIDENT_REFRESH_ROLLED_BACK");
                throw new IllegalStateException("Regulated mutation partial commit is invalid when transaction-mode=REQUIRED.", exception);
            }
            auditFailure(command, document, exception, exception.reasonCode());
            document.setResponseSnapshot(exception.responseSnapshot());
            document.setDegradationReason(exception.reasonCode());
            document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
            transition(document, RegulatedMutationState.COMMITTED_DEGRADED, exception.reasonCode());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(command, document, exception, BUSINESS_WRITE_FAILED);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
            transition(document, RegulatedMutationState.FAILED, BUSINESS_WRITE_FAILED);
            throw exception;
        } catch (Error error) {
            auditFailure(command, document, error, BUSINESS_WRITE_FAILED);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
            transition(document, RegulatedMutationState.FAILED, BUSINESS_WRITE_FAILED);
            throw error;
        }
    }

    private <R, S> void auditFailure(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            Throwable originalFailure,
            String reasonCode
    ) {
        try {
            document.setFailedAuditId(auditPhaseService.recordPhase(
                    document,
                    command.action(),
                    command.resourceType(),
                    AuditOutcome.FAILED,
                    reasonCode
            ));
        } catch (RuntimeException auditFailure) {
            originalFailure.addSuppressed(auditFailure);
        }
    }

    private void markBusinessCommitted(
            RegulatedMutationCommandDocument document,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        document.setResponseSnapshot(pendingSnapshot);
        document.setOutboxEventId(pendingSnapshot.decisionEventId());
        document.setLocalCommitMarker("LOCAL_COMMITTED");
        document.setLocalCommittedAt(Instant.now());
        transition(document, RegulatedMutationState.SUCCESS_AUDIT_PENDING, null);
    }

    private <R, S> RegulatedMutationResult<S> retrySuccessAuditOnly(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        S response = command.responseRestorer().restore(document.getResponseSnapshot());
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.SUCCESS, null);
            document.setSuccessAuditId(auditId);
            document.setSuccessAuditRecorded(true);
            transition(document, RegulatedMutationState.SUCCESS_AUDIT_RECORDED, null);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
            transition(document, RegulatedMutationState.EVIDENCE_PENDING, null);
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, response);
        } catch (RuntimeException exception) {
            document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
            document.setDegradationReason(POST_COMMIT_AUDIT_DEGRADED);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
            transition(document, RegulatedMutationState.COMMITTED_DEGRADED, POST_COMMIT_AUDIT_DEGRADED);
            recordPostCommitDegraded(command, document);
            if (bankModeFailClosed) {
                throw new PostCommitEvidenceIncompleteException();
            }
            log.warn("Regulated mutation committed with degraded evidence: reason=POST_COMMIT_AUDIT_DEGRADED");
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.COMMITTED_DEGRADED,
                    command.statusResponseFactory().response(RegulatedMutationState.COMMITTED_DEGRADED)
            );
        }
    }

    private <R, S> S writeSuccessAudit(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            R result,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.SUCCESS, null);
            document.setSuccessAuditId(auditId);
            document.setSuccessAuditRecorded(true);
            transition(document, RegulatedMutationState.SUCCESS_AUDIT_RECORDED, null);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
            transition(document, RegulatedMutationState.EVIDENCE_PENDING, null);
            return null;
        } catch (RuntimeException exception) {
            S incompleteResponse = command.responseMapper().response(result, RegulatedMutationState.COMMITTED_DEGRADED);
            RegulatedMutationResponseSnapshot incomplete = command.responseSnapshotter().snapshot(incompleteResponse);
            document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
            document.setResponseSnapshot(incomplete);
            document.setDegradationReason(POST_COMMIT_AUDIT_DEGRADED);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
            transition(document, RegulatedMutationState.COMMITTED_DEGRADED, POST_COMMIT_AUDIT_DEGRADED);
            recordPostCommitDegraded(command, document);
            if (bankModeFailClosed) {
                throw new PostCommitEvidenceIncompleteException();
            }
            log.warn("Regulated mutation committed with degraded evidence: reason=POST_COMMIT_AUDIT_DEGRADED");
            return incompleteResponse;
        }
    }

    private <R, S> void recordPostCommitDegraded(RegulatedMutationCommand<R, S> command, RegulatedMutationCommandDocument document) {
        auditDegradationService.recordPostCommitDegraded(
                command.action(),
                command.resourceType(),
                command.resourceId(),
                POST_COMMIT_AUDIT_DEGRADED,
                document.getId()
        );
        metrics.recordPostCommitAuditDegraded(command.action().name());
    }

    private <R, S> RegulatedMutationResult<S> markRecoveryRequired(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            RegulatedMutationState responseState
    ) {
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setDegradationReason("RECOVERY_REQUIRED");
        transition(document, document.getState(), "RECOVERY_REQUIRED");
        return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(responseState));
    }

    private void transition(RegulatedMutationCommandDocument document, RegulatedMutationState state, String lastError) {
        Instant now = Instant.now();
        document.setState(state);
        document.setUpdatedAt(now);
        document.setLastHeartbeatAt(now);
        document.setLastError(lastError);
        commandRepository.save(document);
    }

    private boolean isSafeToExecuteBusinessMutation(RegulatedMutationCommandDocument document) {
        return document.getState() == RegulatedMutationState.REQUESTED
                || document.getState() == RegulatedMutationState.AUDIT_ATTEMPTED;
    }

    private record LocalCommit<R, S>(
            R result,
            S pendingResponse,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
    }

    private static RegulatedMutationReplayPolicyRegistry compatibilityReplayPolicyRegistry() {
        RegulatedMutationLeasePolicy leasePolicy = new RegulatedMutationLeasePolicy();
        return new RegulatedMutationReplayPolicyRegistry(
                List.of(
                        new LegacyRegulatedMutationReplayPolicy(leasePolicy),
                        new EvidenceGatedFinalizeReplayPolicy(leasePolicy)
                ),
                true
        );
    }
}
