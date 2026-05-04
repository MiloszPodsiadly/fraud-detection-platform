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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

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
    private final RegulatedMutationFencedCommandWriter fencedCommandWriter;

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
                new RegulatedMutationReplayResolver(compatibilityReplayPolicyRegistry()),
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics)
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
            RegulatedMutationReplayResolver replayResolver,
            RegulatedMutationFencedCommandWriter fencedCommandWriter
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
        this.fencedCommandWriter = fencedCommandWriter;
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

        RegulatedMutationClaimToken claimToken = claimService.claim(command, idempotencyKey).orElse(null);
        if (claimToken == null) {
            return concurrentResponse(command, idempotencyKey);
        }
        document = claimedDocument(document, claimToken);

        try {
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
                return retrySuccessAuditOnly(command, document, claimToken);
            }

            if (!isSafeToExecuteBusinessMutation(document)) {
                return markRecoveryRequired(command, document, claimToken, LegacyRegulatedMutationReplayPolicy.legacyRecoveryState(document));
            }

            if (!document.isAttemptedAuditRecorded()) {
                writeAttemptedAudit(command, document, claimToken);
            }
            RegulatedMutationCommandDocument claimedDocument = document;
            LocalCommit<R, S> localCommit = transactionRunner.runLocalCommit(() -> {
                R result = executeBusinessMutation(command, claimedDocument, claimToken);
                S pendingResponse = command.responseMapper().response(result, RegulatedMutationState.EVIDENCE_PENDING);
                RegulatedMutationResponseSnapshot pendingSnapshot = command.responseSnapshotter().snapshot(pendingResponse);
                markBusinessCommitted(claimedDocument, claimToken, pendingSnapshot);
                return new LocalCommit<>(result, pendingResponse, pendingSnapshot);
            });
            S finalResponse = writeSuccessAudit(command, document, claimToken, localCommit.result(), localCommit.pendingSnapshot());
            if (finalResponse != null) {
                return new RegulatedMutationResult<>(document.getState(), finalResponse);
            }
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, localCommit.pendingResponse());
        } catch (StaleRegulatedMutationLeaseException exception) {
            return staleLeaseResponse(command, idempotencyKey);
        }
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

    private RegulatedMutationCommandDocument claimedDocument(
            RegulatedMutationCommandDocument fallback,
            RegulatedMutationClaimToken claimToken
    ) {
        RegulatedMutationCommandDocument document = commandRepository.findById(claimToken.commandId()).orElse(fallback);
        document.setId(claimToken.commandId());
        document.setLeaseOwner(claimToken.leaseOwner());
        document.setLeaseExpiresAt(claimToken.leaseExpiresAt());
        document.setExecutionStatus(claimToken.expectedExecutionStatus());
        document.setAttemptCount(claimToken.attemptCount());
        return document;
    }

    private <R, S> RegulatedMutationResult<S> staleLeaseResponse(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
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
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken
    ) {
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.ATTEMPTED, null);
            document.setAttemptedAuditId(auditId);
            document.setAttemptedAuditRecorded(true);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.AUDIT_ATTEMPTED,
                    document.getExecutionStatus(),
                    null,
                    update -> update
                            .set("attempted_audit_id", document.getAttemptedAuditId())
                            .set("attempted_audit_recorded", true)
            );
        } catch (RuntimeException exception) {
            document.setDegradationReason(ATTEMPTED_AUDIT_UNAVAILABLE);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.REJECTED,
                    RegulatedMutationExecutionStatus.FAILED,
                    ATTEMPTED_AUDIT_UNAVAILABLE,
                    update -> update.set("degradation_reason", ATTEMPTED_AUDIT_UNAVAILABLE)
            );
            throw exception;
        }
    }

    private <R, S> R executeBusinessMutation(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken
    ) {
        transition(document, claimToken, RegulatedMutationState.BUSINESS_COMMITTING, document.getExecutionStatus(), null);
        try {
            R result = command.mutation().execute(new RegulatedMutationExecutionContext(document.getId()));
            transition(document, claimToken, RegulatedMutationState.BUSINESS_COMMITTED, document.getExecutionStatus(), null);
            return result;
        } catch (RegulatedMutationPartialCommitException exception) {
            if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED) {
                auditFailure(command, document, exception, "TRUST_INCIDENT_REFRESH_ROLLED_BACK");
                document.setDegradationReason("TRUST_INCIDENT_REFRESH_ROLLED_BACK");
                transition(
                        document,
                        claimToken,
                        RegulatedMutationState.FAILED,
                        RegulatedMutationExecutionStatus.FAILED,
                        "TRUST_INCIDENT_REFRESH_ROLLED_BACK",
                        failureUpdate(document, "TRUST_INCIDENT_REFRESH_ROLLED_BACK")
                );
                throw new IllegalStateException("Regulated mutation partial commit is invalid when transaction-mode=REQUIRED.", exception);
            }
            auditFailure(command, document, exception, exception.reasonCode());
            document.setResponseSnapshot(exception.responseSnapshot());
            document.setDegradationReason(exception.reasonCode());
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.COMMITTED_DEGRADED,
                    RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                    exception.reasonCode(),
                    update -> {
                        update.set("response_snapshot", document.getResponseSnapshot());
                        update.set("degradation_reason", exception.reasonCode());
                        setFailedAudit(update, document);
                    }
            );
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(command, document, exception, BUSINESS_WRITE_FAILED);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.FAILED,
                    RegulatedMutationExecutionStatus.FAILED,
                    BUSINESS_WRITE_FAILED,
                    failureUpdate(document, BUSINESS_WRITE_FAILED)
            );
            throw exception;
        } catch (Error error) {
            auditFailure(command, document, error, BUSINESS_WRITE_FAILED);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.FAILED,
                    RegulatedMutationExecutionStatus.FAILED,
                    BUSINESS_WRITE_FAILED,
                    failureUpdate(document, BUSINESS_WRITE_FAILED)
            );
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
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        document.setResponseSnapshot(pendingSnapshot);
        document.setOutboxEventId(pendingSnapshot.decisionEventId());
        document.setLocalCommitMarker("LOCAL_COMMITTED");
        document.setLocalCommittedAt(Instant.now());
        transition(
                document,
                claimToken,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                document.getExecutionStatus(),
                null,
                update -> update
                        .set("public_status", document.getPublicStatus())
                        .set("response_snapshot", document.getResponseSnapshot())
                        .set("outbox_event_id", document.getOutboxEventId())
                        .set("local_commit_marker", document.getLocalCommitMarker())
                        .set("local_committed_at", document.getLocalCommittedAt())
        );
    }

    private <R, S> RegulatedMutationResult<S> retrySuccessAuditOnly(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken
    ) {
        S response = command.responseRestorer().restore(document.getResponseSnapshot());
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.SUCCESS, null);
            document.setSuccessAuditId(auditId);
            document.setSuccessAuditRecorded(true);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
                    document.getExecutionStatus(),
                    null,
                    successAuditUpdate(document)
            );
            transition(document, claimToken, RegulatedMutationState.EVIDENCE_PENDING, RegulatedMutationExecutionStatus.COMPLETED, null);
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, response);
        } catch (RuntimeException exception) {
            document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
            document.setDegradationReason(POST_COMMIT_AUDIT_DEGRADED);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.COMMITTED_DEGRADED,
                    RegulatedMutationExecutionStatus.COMPLETED,
                    POST_COMMIT_AUDIT_DEGRADED,
                    update -> update
                            .set("public_status", document.getPublicStatus())
                            .set("degradation_reason", POST_COMMIT_AUDIT_DEGRADED)
            );
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
            RegulatedMutationClaimToken claimToken,
            R result,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        try {
            String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.SUCCESS, null);
            document.setSuccessAuditId(auditId);
            document.setSuccessAuditRecorded(true);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
                    document.getExecutionStatus(),
                    null,
                    successAuditUpdate(document)
            );
            transition(document, claimToken, RegulatedMutationState.EVIDENCE_PENDING, RegulatedMutationExecutionStatus.COMPLETED, null);
            return null;
        } catch (RuntimeException exception) {
            S incompleteResponse = command.responseMapper().response(result, RegulatedMutationState.COMMITTED_DEGRADED);
            RegulatedMutationResponseSnapshot incomplete = command.responseSnapshotter().snapshot(incompleteResponse);
            document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
            document.setResponseSnapshot(incomplete);
            document.setDegradationReason(POST_COMMIT_AUDIT_DEGRADED);
            transition(
                    document,
                    claimToken,
                    RegulatedMutationState.COMMITTED_DEGRADED,
                    RegulatedMutationExecutionStatus.COMPLETED,
                    POST_COMMIT_AUDIT_DEGRADED,
                    update -> update
                            .set("public_status", document.getPublicStatus())
                            .set("response_snapshot", document.getResponseSnapshot())
                            .set("degradation_reason", POST_COMMIT_AUDIT_DEGRADED)
            );
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
        directRecoveryTransition(document, document.getState(), "RECOVERY_REQUIRED");
        return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(responseState));
    }

    private <R, S> RegulatedMutationResult<S> markRecoveryRequired(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState responseState
    ) {
        document.setDegradationReason("RECOVERY_REQUIRED");
        transition(
                document,
                claimToken,
                document.getState(),
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                update -> update.set("degradation_reason", "RECOVERY_REQUIRED")
        );
        return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(responseState));
    }

    private void transition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            String lastError
    ) {
        transition(document, claimToken, state, executionStatus, lastError, update -> {
        });
    }

    private void transition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            String lastError,
            Consumer<Update> allowedFieldUpdates
    ) {
        RegulatedMutationState expectedState = document.getState();
        RegulatedMutationExecutionStatus expectedExecutionStatus = document.getExecutionStatus();
        fencedCommandWriter.transition(
                claimToken,
                expectedState,
                expectedExecutionStatus,
                state,
                executionStatus,
                lastError,
                allowedFieldUpdates
        );
        applyTransition(document, state, executionStatus, lastError);
    }

    private void directRecoveryTransition(RegulatedMutationCommandDocument document, RegulatedMutationState state, String lastError) {
        applyTransition(document, state, document.getExecutionStatus(), lastError);
        // Non-claimed replay/recovery path: claimed worker transitions must use RegulatedMutationFencedCommandWriter.
        commandRepository.save(document);
    }

    private void applyTransition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            String lastError
    ) {
        Instant now = Instant.now();
        document.setState(state);
        document.setExecutionStatus(executionStatus);
        document.setUpdatedAt(now);
        document.setLastHeartbeatAt(now);
        document.setLastError(lastError);
    }

    private Consumer<Update> failureUpdate(RegulatedMutationCommandDocument document, String reason) {
        return update -> {
            update.set("degradation_reason", reason);
            setFailedAudit(update, document);
        };
    }

    private Consumer<Update> successAuditUpdate(RegulatedMutationCommandDocument document) {
        return update -> {
            update.set("success_audit_id", document.getSuccessAuditId());
            update.set("success_audit_recorded", true);
        };
    }

    private void setFailedAudit(Update update, RegulatedMutationCommandDocument document) {
        if (document.getFailedAuditId() != null) {
            update.set("failed_audit_id", document.getFailedAuditId());
        }
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
