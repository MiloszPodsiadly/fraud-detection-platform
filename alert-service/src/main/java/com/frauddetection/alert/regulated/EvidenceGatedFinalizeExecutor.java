package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.RegulatedMutationPublicStatusProjection;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class EvidenceGatedFinalizeExecutor implements RegulatedMutationExecutor {

    private static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";
    private static final String EVIDENCE_GATED_TRANSACTION_REQUIRED = "EVIDENCE_GATED_TRANSACTION_REQUIRED";
    private static final String EVIDENCE_GATED_FINALIZE_FAILED = "EVIDENCE_GATED_FINALIZE_FAILED";

    private final RegulatedMutationCommandRepository commandRepository;
    private final RegulatedMutationAuditPhaseService auditPhaseService;
    private final AlertServiceMetrics metrics;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final EvidencePreconditionEvaluator evidencePreconditionEvaluator;
    private final RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private final RegulatedMutationClaimService claimService;
    private final RegulatedMutationConflictPolicy conflictPolicy;
    private final RegulatedMutationReplayResolver replayResolver;
    private final EvidenceGatedFinalizeStateMachine stateMachine = new EvidenceGatedFinalizeStateMachine();

    public EvidenceGatedFinalizeExecutor(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            EvidencePreconditionEvaluator evidencePreconditionEvaluator,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration
    ) {
        this(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                metrics,
                transactionRunner,
                publicStatusMapper,
                evidencePreconditionEvaluator,
                localAuditPhaseWriter,
                new RegulatedMutationClaimService(mongoTemplate, leaseDuration),
                new RegulatedMutationConflictPolicy(),
                new RegulatedMutationReplayResolver(compatibilityReplayPolicyRegistry())
        );
    }

    @Autowired
    public EvidenceGatedFinalizeExecutor(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            EvidencePreconditionEvaluator evidencePreconditionEvaluator,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter,
            RegulatedMutationClaimService claimService,
            RegulatedMutationConflictPolicy conflictPolicy,
            RegulatedMutationReplayResolver replayResolver
    ) {
        this.commandRepository = commandRepository;
        this.auditPhaseService = auditPhaseService;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner;
        this.publicStatusMapper = publicStatusMapper;
        this.evidencePreconditionEvaluator = evidencePreconditionEvaluator;
        this.localAuditPhaseWriter = localAuditPhaseWriter;
        this.claimService = claimService;
        this.conflictPolicy = conflictPolicy;
        this.replayResolver = replayResolver;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.SUBMIT_ANALYST_DECISION
                && resourceType == AuditResourceType.ALERT;
    }

    @Override
    public <R, S> RegulatedMutationResult<S> execute(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey,
            RegulatedMutationCommandDocument document
    ) {
        RegulatedMutationResult<S> terminal = terminalOrStatus(command, document);
        if (terminal != null) {
            return terminal;
        }

        document = claimService.claim(command, idempotencyKey).orElse(null);
        if (document == null) {
            return concurrentResponse(command, idempotencyKey);
        }

        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            document.setDegradationReason(EVIDENCE_GATED_TRANSACTION_REQUIRED);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
            transition(document, RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE, EVIDENCE_GATED_TRANSACTION_REQUIRED);
            throw new IllegalStateException("Evidence-gated finalize requires app.regulated-mutations.transaction-mode=REQUIRED.");
        }

        prepareEvidence(command, document);
        return finalizeVisibleMutation(command, document);
    }

    public <R, S> RegulatedMutationResult<S> commit(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey,
            RegulatedMutationCommandDocument document
    ) {
        return execute(command, idempotencyKey, document);
    }

    private <R, S> RegulatedMutationResult<S> terminalOrStatus(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        RegulatedMutationReplayDecision decision = replayResolver.resolve(document, Instant.now());
        return switch (decision.type()) {
            case NONE -> null;
            case ACTIVE_IN_PROGRESS, REJECTED_RESPONSE -> new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(decision.responseState())
            );
            case FINALIZING_REQUIRES_RECOVERY, FINALIZED_VISIBLE_RECOVERY_REQUIRED ->
                    markRecoveryRequired(command, document, decision.reason());
            case FINALIZED_VISIBLE_REPAIRABLE -> {
                metrics.recordEvidenceGatedFinalizeStuckVisible();
                transition(document, RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, null);
                yield replay(command, document);
            }
            case RECOVERY_REQUIRED_RESPONSE -> new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(decision.responseState())
            );
            case REPLAY_SNAPSHOT -> replay(command, document);
        };
    }

    private <R, S> void prepareEvidence(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        if (document.getState() == RegulatedMutationState.REQUESTED) {
            transition(document, RegulatedMutationState.EVIDENCE_PREPARING, null);
        }
        if (!document.isAttemptedAuditRecorded()) {
            try {
                String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.ATTEMPTED, null);
                document.setAttemptedAuditId(auditId);
                document.setAttemptedAuditRecorded(true);
            } catch (RuntimeException exception) {
                document.setDegradationReason(ATTEMPTED_AUDIT_UNAVAILABLE);
                document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
                transition(document, RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE, ATTEMPTED_AUDIT_UNAVAILABLE);
                throw exception;
            }
        }
        EvidencePreconditionResult precondition = evidencePreconditionEvaluator.evaluate(command, document);
        if (precondition.status() != EvidencePreconditionStatus.SATISFIED) {
            document.setDegradationReason(precondition.reasonCode());
            document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
            RegulatedMutationState rejectedState = switch (precondition.status()) {
                case REJECTED_EVIDENCE_UNAVAILABLE -> RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE;
                case FAILED_BUSINESS_VALIDATION -> RegulatedMutationState.FAILED_BUSINESS_VALIDATION;
                case FINALIZE_RECOVERY_REQUIRED -> RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED;
                case SATISFIED -> throw new IllegalStateException("Unexpected satisfied precondition.");
            };
            transition(document, rejectedState, precondition.reasonCode());
            metrics.recordEvidenceGatedFinalizeRejected(precondition.reasonCode());
            throw new IllegalStateException("FDP-29 evidence precondition failed: " + precondition.reasonCode());
        }
        if (document.getState() == RegulatedMutationState.EVIDENCE_PREPARING) {
            transition(document, RegulatedMutationState.EVIDENCE_PREPARED, null);
        }
    }

    private <R, S> RegulatedMutationResult<S> finalizeVisibleMutation(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        transition(document, RegulatedMutationState.FINALIZING, null);
        try {
            LocalCommit<R, S> localCommit = transactionRunner.runLocalCommit(() -> {
                R result = command.mutation().execute(new RegulatedMutationExecutionContext(document.getId()));
                S response = command.responseMapper().response(result, RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
                RegulatedMutationResponseSnapshot snapshot = command.responseSnapshotter().snapshot(response);
                String auditId = localSuccessAudit(command, document);
                document.setSuccessAuditId(auditId);
                document.setSuccessAuditRecorded(true);
                document.setResponseSnapshot(snapshot);
                document.setOutboxEventId(snapshot.decisionEventId());
                document.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
                document.setLocalCommittedAt(Instant.now());
                document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
                transition(document, RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, null);
                return new LocalCommit<>(result, response, snapshot);
            });
            return new RegulatedMutationResult<>(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, localCommit.pendingResponse());
        } catch (RuntimeException exception) {
            RegulatedMutationCommandDocument persisted = reloadForRecovery(document);
            persisted.setDegradationReason(EVIDENCE_GATED_FINALIZE_FAILED);
            persisted.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
            transition(persisted, RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED, EVIDENCE_GATED_FINALIZE_FAILED);
            metrics.recordEvidenceGatedFinalizeTransactionRollback(EVIDENCE_GATED_FINALIZE_FAILED);
            throw exception;
        }
    }

    private <R, S> RegulatedMutationResult<S> markRecoveryRequired(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            String reason
    ) {
        document.setDegradationReason(reason);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        transition(document, RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED, reason);
        metrics.recordEvidenceGatedFinalizeRecoveryRequired(reason);
        return new RegulatedMutationResult<>(
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                command.statusResponseFactory().response(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED)
        );
    }

    private <R, S> RegulatedMutationResult<S> concurrentResponse(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        RegulatedMutationCommandDocument current = commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> conflictPolicy.existingOrConflict(existing, command))
                .orElseThrow(() -> new MissingIdempotencyKeyException());
        RegulatedMutationResult<S> terminal = terminalOrStatus(command, current);
        if (terminal != null) {
            return terminal;
        }
        return new RegulatedMutationResult<>(
                current.getState(),
                command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
        );
    }

    private RegulatedMutationCommandDocument reloadForRecovery(RegulatedMutationCommandDocument document) {
        return commandRepository.findById(document.getId())
                .orElseGet(() -> commandRepository.findByIdempotencyKey(document.getIdempotencyKey())
                        .orElseThrow(() -> new IllegalStateException("Regulated mutation command unavailable for recovery.")));
    }

    private <R, S> String localSuccessAudit(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        if (localAuditPhaseWriter == null) {
            throw new IllegalStateException("FDP-29 evidence-gated finalize requires a local audit phase writer.");
        }
        return localAuditPhaseWriter.recordSuccessPhase(document, command.action(), command.resourceType());
    }

    private void transition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationState state,
            String lastError
    ) {
        RegulatedMutationState previous = document.getState();
        stateMachine.requireTransition(document.getState(), state);
        document.setPublicStatus(publicStatusMapper.submitDecisionStatus(state, document.mutationModelVersionOrLegacy()));
        Instant now = Instant.now();
        document.setState(state);
        document.setUpdatedAt(now);
        document.setLastHeartbeatAt(now);
        document.setLastError(lastError);
        commandRepository.save(document);
        metrics.recordEvidenceGatedFinalizeStateTransition(previous, state, lastError == null ? "SUCCESS" : "FAILED");
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
