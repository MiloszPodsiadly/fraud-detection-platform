package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class MongoRegulatedMutationCoordinator implements RegulatedMutationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MongoRegulatedMutationCoordinator.class);
    private static final String BUSINESS_WRITE_FAILED = "BUSINESS_WRITE_FAILED";
    private static final String POST_COMMIT_AUDIT_DEGRADED = "POST_COMMIT_AUDIT_DEGRADED";

    private final RegulatedMutationCommandRepository commandRepository;
    private final AuditService auditService;
    private final AuditDegradationService auditDegradationService;
    private final AlertServiceMetrics metrics;
    private final boolean bankModeFailClosed;

    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            AuditService auditService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.commandRepository = commandRepository;
        this.auditService = auditService;
        this.auditDegradationService = auditDegradationService;
        this.metrics = metrics;
        this.bankModeFailClosed = bankModeFailClosed;
    }

    @Override
    public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
        String idempotencyKey = normalize(command.idempotencyKey());
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException();
        }

        RegulatedMutationCommandDocument document = createOrReplay(command, idempotencyKey);
        if (document.getResponseSnapshot() != null) {
            return replay(document);
        }

        writeAttemptedAudit(command, document);
        R result = executeBusinessMutation(command, document);
        S pendingResponse = command.responseMapper().response(result, RegulatedMutationState.EVIDENCE_PENDING);
        RegulatedMutationResponseSnapshot pendingSnapshot = command.responseSnapshotter().snapshot(pendingResponse);
        markBusinessCommitted(document, pendingSnapshot);
        S finalResponse = writeSuccessAudit(command, document, result, pendingSnapshot);
        if (finalResponse != null) {
            return new RegulatedMutationResult<>(document.getState(), finalResponse);
        }
        return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, pendingResponse);
    }

    @SuppressWarnings("unchecked")
    private <S> RegulatedMutationResult<S> replay(RegulatedMutationCommandDocument document) {
        if (document.getResponseSnapshot() == null) {
            throw new IllegalStateException("Regulated mutation replay has no response snapshot.");
        }
        SubmitAnalystDecisionResponse response = document.getResponseSnapshot().toSubmitDecisionResponse();
        return new RegulatedMutationResult<>(document.getState(), (S) response);
    }

    private <R, S> RegulatedMutationCommandDocument createOrReplay(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey
    ) {
        return commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> existingOrConflict(existing, command.requestHash()))
                .orElseGet(() -> createCommand(command, idempotencyKey));
    }

    private RegulatedMutationCommandDocument existingOrConflict(RegulatedMutationCommandDocument existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictingIdempotencyKeyException();
        }
        return existing;
    }

    private <R, S> RegulatedMutationCommandDocument createCommand(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        Instant now = Instant.now();
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setIdempotencyKey(idempotencyKey);
        document.setActorId(command.actorId());
        document.setResourceId(command.resourceId());
        document.setResourceType(command.resourceType().name());
        document.setAction(command.action().name());
        document.setCorrelationId(normalize(command.correlationId()));
        document.setRequestHash(command.requestHash());
        document.setState(RegulatedMutationState.REQUESTED);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        try {
            return commandRepository.save(document);
        } catch (DuplicateKeyException duplicate) {
            return commandRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> existingOrConflict(existing, command.requestHash()))
                    .orElseThrow(() -> duplicate);
        }
    }

    private <R, S> void writeAttemptedAudit(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        try {
            auditService.audit(
                    command.action(),
                    command.resourceType(),
                    command.resourceId(),
                    command.correlationId(),
                    command.actorId(),
                    AuditOutcome.ATTEMPTED,
                    null
            );
            document.setAttemptedAuditRecorded(true);
            transition(document, RegulatedMutationState.AUDIT_ATTEMPTED, null);
        } catch (RuntimeException exception) {
            document.setDegradationReason("ATTEMPTED_AUDIT_UNAVAILABLE");
            transition(document, RegulatedMutationState.REJECTED, "ATTEMPTED_AUDIT_UNAVAILABLE");
            throw exception;
        }
    }

    private <R, S> R executeBusinessMutation(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        transition(document, RegulatedMutationState.BUSINESS_COMMITTING, null);
        try {
            R result = command.mutation().execute();
            transition(document, RegulatedMutationState.BUSINESS_COMMITTED, null);
            return result;
        } catch (RuntimeException exception) {
            auditFailure(command, exception);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            transition(document, RegulatedMutationState.FAILED, BUSINESS_WRITE_FAILED);
            throw exception;
        } catch (Error error) {
            auditFailure(command, error);
            document.setDegradationReason(BUSINESS_WRITE_FAILED);
            transition(document, RegulatedMutationState.FAILED, BUSINESS_WRITE_FAILED);
            throw error;
        }
    }

    private <R, S> void auditFailure(RegulatedMutationCommand<R, S> command, Throwable originalFailure) {
        try {
            auditService.audit(
                    command.action(),
                    command.resourceType(),
                    command.resourceId(),
                    command.correlationId(),
                    command.actorId(),
                    AuditOutcome.FAILED,
                    BUSINESS_WRITE_FAILED
            );
        } catch (RuntimeException auditFailure) {
            originalFailure.addSuppressed(auditFailure);
        }
    }

    private <R, S> void markBusinessCommitted(
            RegulatedMutationCommandDocument document,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        document.setResponseSnapshot(pendingSnapshot);
        document.setOutboxEventId(pendingSnapshot.decisionEventId());
        transition(document, RegulatedMutationState.SUCCESS_AUDIT_PENDING, null);
    }

    private <R, S> S writeSuccessAudit(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            R result,
            RegulatedMutationResponseSnapshot pendingSnapshot
    ) {
        try {
            auditService.audit(
                    command.action(),
                    command.resourceType(),
                    command.resourceId(),
                    command.correlationId(),
                    command.actorId(),
                    AuditOutcome.SUCCESS,
                    null
            );
            document.setSuccessAuditRecorded(true);
            transition(document, RegulatedMutationState.SUCCESS_AUDIT_RECORDED, null);
            transition(document, RegulatedMutationState.EVIDENCE_PENDING, null);
            return null;
        } catch (RuntimeException exception) {
            S incompleteResponse = command.responseMapper().response(result, RegulatedMutationState.COMMITTED_DEGRADED);
            RegulatedMutationResponseSnapshot incomplete = command.responseSnapshotter().snapshot(incompleteResponse);
            document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
            document.setResponseSnapshot(incomplete);
            document.setDegradationReason(POST_COMMIT_AUDIT_DEGRADED);
            transition(document, RegulatedMutationState.COMMITTED_DEGRADED, POST_COMMIT_AUDIT_DEGRADED);
            auditDegradationService.recordPostCommitDegraded(
                    command.action(),
                    command.resourceType(),
                    command.resourceId(),
                    POST_COMMIT_AUDIT_DEGRADED
            );
            metrics.recordPostCommitAuditDegraded(command.action().name());
            if (bankModeFailClosed) {
                throw new PostCommitEvidenceIncompleteException();
            }
            log.warn("Regulated mutation committed with degraded evidence: reason=POST_COMMIT_AUDIT_DEGRADED");
            return incompleteResponse;
        }
    }

    private void transition(RegulatedMutationCommandDocument document, RegulatedMutationState state, String lastError) {
        document.setState(state);
        document.setUpdatedAt(Instant.now());
        document.setLastError(lastError);
        commandRepository.save(document);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
