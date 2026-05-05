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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
    private final RegulatedMutationFencedCommandWriter fencedCommandWriter;
    private final RegulatedMutationCheckpointRenewalService checkpointRenewalService;
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
                new RegulatedMutationReplayResolver(compatibilityReplayPolicyRegistry()),
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                RegulatedMutationCheckpointRenewalService.disabled()
        );
    }

    // Compatibility/unit-test constructor only. Production wiring must use Spring-managed checkpoint renewal service.
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
            RegulatedMutationReplayResolver replayResolver,
            RegulatedMutationFencedCommandWriter fencedCommandWriter
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
                claimService,
                conflictPolicy,
                replayResolver,
                fencedCommandWriter,
                RegulatedMutationCheckpointRenewalService.disabled()
        );
    }

    @Autowired
    // Compatibility/unit-test constructor only. Production wiring must use Spring-managed checkpoint renewal service.
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
            RegulatedMutationReplayResolver replayResolver,
            RegulatedMutationFencedCommandWriter fencedCommandWriter,
            RegulatedMutationCheckpointRenewalService checkpointRenewalService
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
        this.fencedCommandWriter = fencedCommandWriter;
        this.checkpointRenewalService = Objects.requireNonNull(
                checkpointRenewalService,
                "FDP-34 production wiring requires checkpoint renewal service."
        );
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

        RegulatedMutationClaimToken claimToken = claimService.claim(command, idempotencyKey).orElse(null);
        if (claimToken == null) {
            return concurrentResponse(command, idempotencyKey);
        }
        document = claimedDocument(document, claimToken);

        try {
            if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
                document.setDegradationReason(EVIDENCE_GATED_TRANSACTION_REQUIRED);
                transition(
                        document,
                        claimToken,
                        RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE,
                        RegulatedMutationExecutionStatus.FAILED,
                        EVIDENCE_GATED_TRANSACTION_REQUIRED,
                        update -> update.set("degradation_reason", EVIDENCE_GATED_TRANSACTION_REQUIRED)
                );
                throw new IllegalStateException("Evidence-gated finalize requires app.regulated-mutations.transaction-mode=REQUIRED.");
            }

            prepareEvidence(command, document, claimToken);
            return finalizeVisibleMutation(command, document, claimToken);
        } catch (StaleRegulatedMutationLeaseException exception) {
            return staleLeaseResponse(command, idempotencyKey);
        }
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
                recoveryTransition(
                        document,
                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                        RegulatedMutationExecutionStatus.COMPLETED,
                        null,
                        update -> update.set(
                                "public_status",
                                publicStatusMapper.submitDecisionStatus(
                                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                                        document.mutationModelVersionOrLegacy()
                                )
                        )
                );
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
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken
    ) {
        if (document.getState() == RegulatedMutationState.REQUESTED) {
            transition(document, claimToken, RegulatedMutationState.EVIDENCE_PREPARING, document.getExecutionStatus(), null);
        }
        if (document.getState() == RegulatedMutationState.EVIDENCE_PREPARING) {
            checkpointRenewalService.beforeEvidencePreparation(claimToken, document);
        }
        if (!document.isAttemptedAuditRecorded()) {
            try {
                String auditId = auditPhaseService.recordPhase(document, command.action(), command.resourceType(), AuditOutcome.ATTEMPTED, null);
                document.setAttemptedAuditId(auditId);
                document.setAttemptedAuditRecorded(true);
            } catch (RuntimeException exception) {
                document.setDegradationReason(ATTEMPTED_AUDIT_UNAVAILABLE);
                transition(
                        document,
                        claimToken,
                        RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE,
                        RegulatedMutationExecutionStatus.FAILED,
                        ATTEMPTED_AUDIT_UNAVAILABLE,
                        update -> update.set("degradation_reason", ATTEMPTED_AUDIT_UNAVAILABLE)
                );
                throw exception;
            }
        }
        EvidencePreconditionResult precondition = evidencePreconditionEvaluator.evaluate(command, document);
        if (precondition.status() != EvidencePreconditionStatus.SATISFIED) {
            document.setDegradationReason(precondition.reasonCode());
            RegulatedMutationState rejectedState = switch (precondition.status()) {
                case REJECTED_EVIDENCE_UNAVAILABLE -> RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE;
                case FAILED_BUSINESS_VALIDATION -> RegulatedMutationState.FAILED_BUSINESS_VALIDATION;
                case FINALIZE_RECOVERY_REQUIRED -> RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED;
                case SATISFIED -> throw new IllegalStateException("Unexpected satisfied precondition.");
            };
            RegulatedMutationExecutionStatus targetStatus = precondition.status() == EvidencePreconditionStatus.FINALIZE_RECOVERY_REQUIRED
                    ? RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
                    : RegulatedMutationExecutionStatus.FAILED;
            transition(
                    document,
                    claimToken,
                    rejectedState,
                    targetStatus,
                    precondition.reasonCode(),
                    update -> update.set("degradation_reason", precondition.reasonCode())
            );
            metrics.recordEvidenceGatedFinalizeRejected(precondition.reasonCode());
            throw new IllegalStateException("FDP-29 evidence precondition failed: " + precondition.reasonCode());
        }
        if (document.getState() == RegulatedMutationState.EVIDENCE_PREPARING) {
            transition(document, claimToken, RegulatedMutationState.EVIDENCE_PREPARED, document.getExecutionStatus(), null);
        }
        if (document.getState() == RegulatedMutationState.EVIDENCE_PREPARED) {
            checkpointRenewalService.afterEvidencePreparedBeforeFinalize(claimToken, document);
        }
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

    private <R, S> RegulatedMutationResult<S> finalizeVisibleMutation(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            RegulatedMutationClaimToken claimToken
    ) {
        transition(document, claimToken, RegulatedMutationState.FINALIZING, document.getExecutionStatus(), null);
        checkpointRenewalService.beforeEvidenceGatedFinalize(claimToken, document);
        try {
            LocalCommit<R, S> localCommit = transactionRunner.runLocalCommit(() -> {
                fencedCommandWriter.validateActiveLease(
                        claimToken,
                        RegulatedMutationState.FINALIZING,
                        document.getExecutionStatus()
                );
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
                transition(
                        document,
                        claimToken,
                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                        RegulatedMutationExecutionStatus.COMPLETED,
                        null,
                        update -> update
                                .set("success_audit_id", document.getSuccessAuditId())
                                .set("success_audit_recorded", true)
                                .set("response_snapshot", document.getResponseSnapshot())
                                .set("outbox_event_id", document.getOutboxEventId())
                                .set("local_commit_marker", document.getLocalCommitMarker())
                                .set("local_committed_at", document.getLocalCommittedAt())
                );
                return new LocalCommit<>(result, response, snapshot);
            });
            return new RegulatedMutationResult<>(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, localCommit.pendingResponse());
        } catch (StaleRegulatedMutationLeaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            RegulatedMutationCommandDocument persisted = reloadForRecovery(document);
            persisted.setDegradationReason(EVIDENCE_GATED_FINALIZE_FAILED);
            transition(
                    persisted,
                    claimToken,
                    RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                    RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                    EVIDENCE_GATED_FINALIZE_FAILED,
                    update -> update.set("degradation_reason", EVIDENCE_GATED_FINALIZE_FAILED)
            );
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
        recoveryTransition(
                document,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                reason,
                update -> {
                    update.set("degradation_reason", reason);
                    update.set(
                            "public_status",
                            publicStatusMapper.submitDecisionStatus(
                                    RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                                    document.mutationModelVersionOrLegacy()
                            )
                    );
                }
        );
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

    private <R, S> RegulatedMutationResult<S> staleLeaseResponse(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
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
        RegulatedMutationState previous = document.getState();
        RegulatedMutationExecutionStatus previousExecutionStatus = document.getExecutionStatus();
        stateMachine.requireTransition(document.getState(), state);
        var publicStatus = publicStatusMapper.submitDecisionStatus(state, document.mutationModelVersionOrLegacy());
        fencedCommandWriter.transition(
                claimToken,
                previous,
                previousExecutionStatus,
                state,
                executionStatus,
                lastError,
                update -> {
                    update.set("public_status", publicStatus);
                    allowedFieldUpdates.accept(update);
                }
        );
        applyTransition(document, state, executionStatus, lastError);
        document.setPublicStatus(publicStatus);
        metrics.recordEvidenceGatedFinalizeStateTransition(previous, state, lastError == null ? "SUCCESS" : "FAILED");
    }

    private void recoveryTransition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            String lastError,
            Consumer<Update> allowedFieldUpdates
    ) {
        RegulatedMutationState previous = document.getState();
        stateMachine.requireTransition(document.getState(), state);
        fencedCommandWriter.recoveryTransition(document, state, executionStatus, lastError, allowedFieldUpdates);
        document.setPublicStatus(publicStatusMapper.submitDecisionStatus(state, document.mutationModelVersionOrLegacy()));
        applyTransition(document, state, executionStatus, lastError);
        metrics.recordEvidenceGatedFinalizeStateTransition(previous, state, lastError == null ? "SUCCESS" : "FAILED");
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
