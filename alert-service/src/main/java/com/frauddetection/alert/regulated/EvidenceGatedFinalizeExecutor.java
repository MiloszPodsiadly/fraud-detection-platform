package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.RegulatedMutationPublicStatusProjection;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class EvidenceGatedFinalizeExecutor implements RegulatedMutationExecutor {

    private static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";
    private static final String EVIDENCE_GATED_TRANSACTION_REQUIRED = "EVIDENCE_GATED_TRANSACTION_REQUIRED";
    private static final String EVIDENCE_GATED_FINALIZE_FAILED = "EVIDENCE_GATED_FINALIZE_FAILED";

    private final RegulatedMutationCommandRepository commandRepository;
    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationAuditPhaseService auditPhaseService;
    private final AlertServiceMetrics metrics;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final EvidencePreconditionEvaluator evidencePreconditionEvaluator;
    private final RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private final Duration leaseDuration;
    private final EvidenceGatedFinalizeStateMachine stateMachine = new EvidenceGatedFinalizeStateMachine();

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
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration
    ) {
        this.commandRepository = commandRepository;
        this.mongoTemplate = mongoTemplate;
        this.auditPhaseService = auditPhaseService;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner;
        this.publicStatusMapper = publicStatusMapper;
        this.evidencePreconditionEvaluator = evidencePreconditionEvaluator;
        this.localAuditPhaseWriter = localAuditPhaseWriter;
        this.leaseDuration = leaseDuration;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1;
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

        document = claim(command, idempotencyKey);
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
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.PROCESSING && !leaseExpired(document, Instant.now())) {
            return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(document.getState()));
        }
        if (document.getState() == RegulatedMutationState.FINALIZING) {
            return markRecoveryRequired(command, document, "FINALIZING_RETRY_REQUIRES_RECONCILIATION");
        }
        if (document.getState() == RegulatedMutationState.FINALIZED_VISIBLE) {
            if (document.getResponseSnapshot() != null
                    && document.getLocalCommitMarker() != null
                    && document.isSuccessAuditRecorded()) {
                metrics.recordEvidenceGatedFinalizeStuckVisible();
                transition(document, RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, null);
                return replay(command, document);
            }
            metrics.recordEvidenceGatedFinalizeStuckVisible();
            return markRecoveryRequired(command, document, "FINALIZED_VISIBLE_MISSING_PROOF");
        }
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
                || document.getState() == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED) {
            return new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED)
            );
        }
        if (document.getResponseSnapshot() != null) {
            return replay(command, document);
        }
        if (document.getState() == RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE
                || document.getState() == RegulatedMutationState.FAILED_BUSINESS_VALIDATION) {
            return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(document.getState()));
        }
        return null;
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

    private <R, S> RegulatedMutationCommandDocument claim(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        Instant now = Instant.now();
        String leaseOwner = UUID.randomUUID().toString();
        Criteria claimable = new Criteria().orOperator(
                Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.NEW),
                new Criteria().andOperator(
                        Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.PROCESSING),
                        Criteria.where("lease_expires_at").lte(now)
                )
        );
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("idempotency_key").is(idempotencyKey),
                Criteria.where("request_hash").is(command.requestHash()),
                claimable
        ));
        Update update = new Update()
                .set("execution_status", RegulatedMutationExecutionStatus.PROCESSING)
                .set("lease_owner", leaseOwner)
                .set("lease_expires_at", now.plus(leaseDuration))
                .set("last_heartbeat_at", now)
                .set("updated_at", now)
                .inc("attempt_count", 1);
        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                RegulatedMutationCommandDocument.class
        );
    }

    private <R, S> RegulatedMutationResult<S> concurrentResponse(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        RegulatedMutationCommandDocument current = commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> existingOrConflict(existing, command))
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

    private <R, S> RegulatedMutationCommandDocument existingOrConflict(
            RegulatedMutationCommandDocument existing,
            RegulatedMutationCommand<R, S> command
    ) {
        if (!existing.getRequestHash().equals(command.requestHash())
                || (existing.getIntentActorId() != null && !existing.getIntentActorId().equals(command.actorId()))) {
            throw new ConflictingIdempotencyKeyException();
        }
        return existing;
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

    private boolean leaseExpired(RegulatedMutationCommandDocument document, Instant now) {
        return document.getLeaseExpiresAt() == null || !document.getLeaseExpiresAt().isAfter(now);
    }

    private record LocalCommit<R, S>(
            R result,
            S pendingResponse,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
    }
}
