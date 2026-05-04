package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.RegulatedMutationPublicStatusProjection;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class LegacyRegulatedMutationExecutor implements RegulatedMutationExecutor {

    private static final Logger log = LoggerFactory.getLogger(LegacyRegulatedMutationExecutor.class);
    private static final String BUSINESS_WRITE_FAILED = "BUSINESS_WRITE_FAILED";
    private static final String POST_COMMIT_AUDIT_DEGRADED = "POST_COMMIT_AUDIT_DEGRADED";
    private static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";

    private final RegulatedMutationCommandRepository commandRepository;
    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationAuditPhaseService auditPhaseService;
    private final AuditDegradationService auditDegradationService;
    private final AlertServiceMetrics metrics;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final boolean bankModeFailClosed;
    private final Duration leaseDuration;

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
        this.commandRepository = commandRepository;
        this.mongoTemplate = mongoTemplate;
        this.auditPhaseService = auditPhaseService;
        this.auditDegradationService = auditDegradationService;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner;
        this.publicStatusMapper = publicStatusMapper;
        this.bankModeFailClosed = bankModeFailClosed;
        this.leaseDuration = leaseDuration;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION;
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

        document = claim(command, idempotencyKey);
        if (document == null) {
            return concurrentResponse(command, idempotencyKey);
        }

        if (document.getResponseSnapshot() != null && !needsSuccessAuditRetry(document)) {
            return replay(command, document);
        }
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED) {
            return new RegulatedMutationResult<>(
                    document.getState(),
                    command.statusResponseFactory().response(recoveryState(document))
            );
        }

        if (document.getState() == RegulatedMutationState.SUCCESS_AUDIT_PENDING && document.getResponseSnapshot() != null) {
            return retrySuccessAuditOnly(command, document);
        }

        if (!isSafeToExecuteBusinessMutation(document)) {
            return markRecoveryRequired(command, document, recoveryState(document));
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
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.PROCESSING && !leaseExpired(document, Instant.now())) {
            return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(RegulatedMutationState.REQUESTED));
        }
        if (document.getResponseSnapshot() != null && !needsSuccessAuditRetry(document)) {
            return replay(command, document);
        }
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED) {
            return new RegulatedMutationResult<>(document.getState(), command.statusResponseFactory().response(recoveryState(document)));
        }
        if (requiresRecoveryWithoutSnapshot(document)) {
            return markRecoveryRequired(command, document, recoveryState(document));
        }
        return null;
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

    private boolean needsSuccessAuditRetry(RegulatedMutationCommandDocument document) {
        return document.getState() == RegulatedMutationState.SUCCESS_AUDIT_PENDING
                && !document.isSuccessAuditRecorded();
    }

    private boolean requiresRecoveryWithoutSnapshot(RegulatedMutationCommandDocument document) {
        return switch (document.getState()) {
            case BUSINESS_COMMITTING, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING, COMMITTED_DEGRADED, EVIDENCE_PENDING,
                 EVIDENCE_CONFIRMED, COMMITTED, FINALIZED_VISIBLE, FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                 FINALIZED_EVIDENCE_CONFIRMED -> document.getResponseSnapshot() == null;
            default -> false;
        };
    }

    private RegulatedMutationState recoveryState(RegulatedMutationCommandDocument document) {
        if (document.getState() == RegulatedMutationState.BUSINESS_COMMITTING) {
            return RegulatedMutationState.BUSINESS_COMMITTING;
        }
        return RegulatedMutationState.FAILED;
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
