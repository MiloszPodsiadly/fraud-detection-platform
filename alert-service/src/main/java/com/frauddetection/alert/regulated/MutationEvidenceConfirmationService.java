package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.external.AuditEventExternalEvidenceStatus;
import com.frauddetection.alert.audit.external.AuditEventPublicationStatusLookup;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MutationEvidenceConfirmationService {

    private final RegulatedMutationCommandRepository commandRepository;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditEventPublicationStatusLookup publicationStatusLookup;
    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final boolean externalAnchorRequired;
    private final boolean signatureRequired;

    @Autowired
    public MutationEvidenceConfirmationService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            AuditEventRepository auditEventRepository,
            AuditEventPublicationStatusLookup publicationStatusLookup,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean externalAnchorRequired,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signatureRequired
    ) {
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.auditEventRepository = auditEventRepository;
        this.publicationStatusLookup = publicationStatusLookup;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.publicStatusMapper = new RegulatedMutationPublicStatusMapper();
        this.externalAnchorRequired = externalAnchorRequired;
        this.signatureRequired = signatureRequired;
    }

    MutationEvidenceConfirmationService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            AlertServiceMetrics metrics,
            boolean externalAnchorRequired,
            boolean signatureRequired
    ) {
        this(commandRepository, outboxRepository, null, null, null, metrics, externalAnchorRequired, signatureRequired);
    }

    public int confirmPendingEvidence(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int promoted = 0;
        List<RegulatedMutationCommandDocument> commands = commandRepository.findTop100ByStateInAndUpdatedAtBefore(
                List.of(
                        RegulatedMutationState.FINALIZED_VISIBLE,
                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                ),
                java.time.Instant.now().plusSeconds(1)
        );
        int boundedLimit = Math.min(limit, 100);
        metrics.recordEvidenceConfirmationPending(commands.size());
        for (RegulatedMutationCommandDocument command : commands.stream().limit(boundedLimit).toList()) {
            if (command.getMutationModelVersion() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
                throw new IllegalStateException("Unsupported persisted regulated mutation model version.");
            }
            promoted += confirmEvidenceGatedCommand(command);
        }
        return promoted;
    }

    private int confirmEvidenceGatedCommand(RegulatedMutationCommandDocument command) {
        EvidenceDecision decision = decision(command);
        if (decision.outcome() == EvidenceConfirmationOutcome.CONFIRMED) {
            command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
            command.setPublicStatus(publicStatusMapper.submitDecisionStatus(command));
            command.setUpdatedAt(java.time.Instant.now());
            commandRepository.save(command);
            updateAlertOperationStatus(command, command.getPublicStatus());
            return 1;
        }
        if (decision.outcome() == EvidenceConfirmationOutcome.PENDING) {
            if (command.getState() == RegulatedMutationState.FINALIZED_VISIBLE) {
                command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
                command.setPublicStatus(publicStatusMapper.currentStatus(command));
                command.setUpdatedAt(java.time.Instant.now());
                commandRepository.save(command);
                updateAlertOperationStatus(command, command.getPublicStatus());
                metrics.recordEvidenceGatedFinalizeStuckVisible();
            }
            if (decision.reason() != null) {
                metrics.recordEvidenceConfirmationFailed(decision.reason());
            }
            return 0;
        }
        if (decision.outcome() == EvidenceConfirmationOutcome.FAILED) {
            command.setState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
            command.setPublicStatus(publicStatusMapper.currentStatus(command));
            command.setDegradationReason(decision.reason());
            command.setLastError(decision.reason());
            command.setUpdatedAt(java.time.Instant.now());
            commandRepository.save(command);
            updateAlertOperationStatus(command, command.getPublicStatus());
            metrics.recordEvidenceGatedFinalizeRecoveryRequired(decision.reason());
            metrics.recordEvidenceConfirmationFailed(decision.reason());
        }
        return 0;
    }

    public EvidenceDecision decision(RegulatedMutationCommandDocument command) {
        if (command == null || command.getLocalCommitMarker() == null) {
            return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "LOCAL_COMMIT_MISSING");
        }
        if (!command.isSuccessAuditRecorded() || command.getSuccessAuditId() == null) {
            return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "SUCCESS_AUDIT_MISSING");
        }
        RegulatedMutationDefinition definition;
        try {
            definition = RegulatedMutationDefinitions.requireSupported(
                    AuditAction.valueOf(command.getAction()),
                    AuditResourceType.valueOf(command.getResourceType())
            );
        } catch (RuntimeException exception) {
            return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "UNSUPPORTED_OPERATION");
        }
        if (definition.requiresTransactionalOutbox()) {
            TransactionalOutboxRecordDocument outbox = outboxRepository.findByMutationCommandId(command.getId()).orElse(null);
            if (outbox == null) {
                return new EvidenceDecision(
                        EvidenceConfirmationOutcome.FAILED,
                        "OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT"
                );
            }
            if (outbox.getStatus() == TransactionalOutboxStatus.FAILED_TERMINAL) {
                return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "OUTBOX_FAILED_TERMINAL");
            }
            if (outbox.getStatus() != TransactionalOutboxStatus.PUBLISHED) {
                return new EvidenceDecision(EvidenceConfirmationOutcome.PENDING, "OUTBOX_NOT_YET_PUBLISHED");
            }
        }
        if (externalAnchorRequired) {
            AuditEventExternalEvidenceStatus status = externalEvidenceStatus(command);
            if (status == null || !status.externalPublished()) {
                return new EvidenceDecision(EvidenceConfirmationOutcome.PENDING, "EXTERNAL_ANCHOR_MISSING");
            }
        }
        if (signatureRequired) {
            AuditEventExternalEvidenceStatus status = externalEvidenceStatus(command);
            if (status == null || status.signatureStatus() == null || status.signatureStatus().isBlank()) {
                return new EvidenceDecision(EvidenceConfirmationOutcome.PENDING, "SIGNATURE_UNAVAILABLE");
            }
            if (!status.signatureValid()) {
                return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "SIGNATURE_INVALID");
            }
        }
        return new EvidenceDecision(EvidenceConfirmationOutcome.CONFIRMED, null);
    }

    private AuditEventExternalEvidenceStatus externalEvidenceStatus(RegulatedMutationCommandDocument command) {
        if (auditEventRepository == null || publicationStatusLookup == null || command.getSuccessAuditId() == null) {
            return null;
        }
        AuditEventDocument successAudit = auditEventRepository.findByAuditId(command.getSuccessAuditId()).orElse(null);
        if (successAudit == null) {
            return null;
        }
        Map<String, AuditEventExternalEvidenceStatus> statuses = publicationStatusLookup.evidenceStatusesByAuditEventId(List.of(successAudit));
        return statuses.get(successAudit.auditId());
    }

    private void updateAlertOperationStatus(
            RegulatedMutationCommandDocument command,
            SubmitDecisionOperationStatus status
    ) {
        if (mongoTemplate == null || command.getResourceId() == null || command.getResourceId().isBlank()) {
            return;
        }
        if (!AuditAction.SUBMIT_ANALYST_DECISION.name().equals(command.getAction())
                || !AuditResourceType.ALERT.name().equals(command.getResourceType())) {
            return;
        }
        try {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(command.getResourceId())),
                    new Update().set("decisionOperationStatus", status.name()),
                    AlertDocument.class
            );
        } catch (DataAccessException exception) {
            metrics.recordOutboxProjectionMismatch(1);
        }
    }

    public enum EvidenceConfirmationOutcome {
        CONFIRMED,
        PENDING,
        FAILED
    }

    public record EvidenceDecision(EvidenceConfirmationOutcome outcome, String reason) {
    }
}
