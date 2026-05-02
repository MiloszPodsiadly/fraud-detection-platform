package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
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
        int promoted = 0;
        List<RegulatedMutationCommandDocument> commands = commandRepository.findTop100ByStateInAndUpdatedAtBefore(
                List.of(RegulatedMutationState.EVIDENCE_PENDING),
                java.time.Instant.now().plusSeconds(1)
        );
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        metrics.recordEvidenceConfirmationPending(commands.size());
        for (RegulatedMutationCommandDocument command : commands.stream().limit(boundedLimit).toList()) {
            EvidenceDecision decision = decision(command);
            if (decision.state() == RegulatedMutationState.EVIDENCE_CONFIRMED) {
                command.setState(RegulatedMutationState.EVIDENCE_CONFIRMED);
                command.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
                command.setUpdatedAt(java.time.Instant.now());
                commandRepository.save(command);
                updateAlertOperationStatus(command, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
                promoted++;
            } else if (decision.state() == RegulatedMutationState.COMMITTED_DEGRADED) {
                command.setState(RegulatedMutationState.COMMITTED_DEGRADED);
                command.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
                command.setDegradationReason(decision.reason());
                command.setLastError(decision.reason());
                command.setUpdatedAt(java.time.Instant.now());
                commandRepository.save(command);
                updateAlertOperationStatus(command, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
                metrics.recordEvidenceConfirmationFailed(decision.reason());
            } else if (decision.reason() != null) {
                metrics.recordEvidenceConfirmationFailed(decision.reason());
            }
        }
        return promoted;
    }

    public EvidenceDecision decision(RegulatedMutationCommandDocument command) {
        if (command == null || command.getLocalCommitMarker() == null) {
            return new EvidenceDecision(RegulatedMutationState.FAILED, "LOCAL_COMMIT_MISSING");
        }
        if (!command.isSuccessAuditRecorded() || command.getSuccessAuditId() == null) {
            return new EvidenceDecision(RegulatedMutationState.COMMITTED_DEGRADED, "SUCCESS_AUDIT_MISSING");
        }
        TransactionalOutboxRecordDocument outbox = outboxRepository.findByMutationCommandId(command.getId()).orElse(null);
        if (outbox == null || outbox.getStatus() != TransactionalOutboxStatus.PUBLISHED) {
            return new EvidenceDecision(RegulatedMutationState.EVIDENCE_PENDING, "OUTBOX_NOT_PUBLISHED");
        }
        if (externalAnchorRequired) {
            AuditEventExternalEvidenceStatus status = externalEvidenceStatus(command);
            if (status == null || !status.externalPublished()) {
                return new EvidenceDecision(RegulatedMutationState.EVIDENCE_PENDING, "EXTERNAL_ANCHOR_MISSING");
            }
        }
        if (signatureRequired) {
            AuditEventExternalEvidenceStatus status = externalEvidenceStatus(command);
            if (status == null || !status.signatureValid()) {
                return new EvidenceDecision(RegulatedMutationState.EVIDENCE_PENDING, "SIGNATURE_UNAVAILABLE");
            }
        }
        return new EvidenceDecision(RegulatedMutationState.EVIDENCE_CONFIRMED, null);
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

    public record EvidenceDecision(RegulatedMutationState state, String reason) {
    }
}
