package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.external.AuditEventExternalEvidenceStatus;
import com.frauddetection.alert.audit.external.AuditEventPublicationStatusLookup;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxAlertProjectionPolicy;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.OutboxPublicationConfirmationProvenance;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.mongodb.client.result.UpdateResult;
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
    private final RegulatedMutationFencedCommandWriter fencedCommandWriter;
    private final RegulatedMutationDurableLocalFinalizationProof durableLocalFinalizationProof;
    private final RegulatedMutationTransactionRunner transactionRunner;
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
            RegulatedMutationFencedCommandWriter fencedCommandWriter,
            RegulatedMutationDurableLocalFinalizationProof durableLocalFinalizationProof,
            RegulatedMutationTransactionRunner transactionRunner,
            @Value("${app.audit.external-anchoring.publication.required:false}") boolean externalAnchorRequired,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signatureRequired
    ) {
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.auditEventRepository = auditEventRepository;
        this.publicationStatusLookup = publicationStatusLookup;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.publicStatusMapper = new RegulatedMutationPublicStatusMapper();
        this.fencedCommandWriter = fencedCommandWriter;
        this.durableLocalFinalizationProof = durableLocalFinalizationProof;
        this.transactionRunner = transactionRunner;
        this.externalAnchorRequired = externalAnchorRequired;
        this.signatureRequired = signatureRequired;
    }

    MutationEvidenceConfirmationService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            AuditEventRepository auditEventRepository,
            AuditEventPublicationStatusLookup publicationStatusLookup,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            RegulatedMutationFencedCommandWriter fencedCommandWriter,
            RegulatedMutationDurableLocalFinalizationProof durableLocalFinalizationProof,
            boolean externalAnchorRequired,
            boolean signatureRequired
    ) {
        this(commandRepository, outboxRepository, auditEventRepository, publicationStatusLookup, mongoTemplate,
                metrics, fencedCommandWriter, durableLocalFinalizationProof,
                new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null),
                externalAnchorRequired, signatureRequired);
    }

    MutationEvidenceConfirmationService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            AlertServiceMetrics metrics,
            RegulatedMutationFencedCommandWriter fencedCommandWriter,
            RegulatedMutationDurableLocalFinalizationProof durableLocalFinalizationProof,
            boolean externalAnchorRequired,
            boolean signatureRequired
    ) {
        this(commandRepository, outboxRepository, null, null, null, metrics, fencedCommandWriter,
                durableLocalFinalizationProof,
                new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null),
                externalAnchorRequired, signatureRequired);
    }

    public int confirmPendingEvidence(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int promoted = 0;
        List<RegulatedMutationCommandDocument> commands = commandRepository.findTop100ByStateInAndUpdatedAtBefore(
                List.of(
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
            command.requireRevision();
            try {
                promoted += confirmEvidenceGatedCommand(command);
            } catch (RegulatedMutationRecoveryWriteConflictException conflict) {
                // Another worker advanced the command after this batch read; keep the newer durable state.
            } catch (RegulatedMutationAlertProjectionException projectionFailure) {
                // The transaction rolled back, so leave this command retryable without starving the batch.
            }
        }
        return promoted;
    }

    private int confirmEvidenceGatedCommand(RegulatedMutationCommandDocument command) {
        EvidenceDecision decision = decision(command);
        if (decision.outcome() == EvidenceConfirmationOutcome.CONFIRMED) {
            AlertStatusProjectionResult projectionResult = transactionRunner.runLocalCommit(() -> {
                transition(
                        command,
                        RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                        null,
                        null
                );
                return updateAlertOperationStatus(command, command.getPublicStatus());
            });
            recordSuccessfulProjection(projectionResult);
            return 1;
        }
        if (decision.outcome() == EvidenceConfirmationOutcome.PENDING) {
            if (decision.reason() != null) {
                metrics.recordEvidenceConfirmationFailed(decision.reason());
            }
            return 0;
        }
        if (decision.outcome() == EvidenceConfirmationOutcome.FAILED) {
            AlertStatusProjectionResult projectionResult = transactionRunner.runLocalCommit(() -> {
                transition(
                        command,
                        RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                        decision.reason(),
                        decision.reason()
                );
                return updateAlertOperationStatus(command, command.getPublicStatus());
            });
            recordSuccessfulProjection(projectionResult);
            metrics.recordEvidenceGatedFinalizeRecoveryRequired(decision.reason());
            metrics.recordEvidenceConfirmationFailed(decision.reason());
        }
        return 0;
    }

    private void transition(
            RegulatedMutationCommandDocument command,
            RegulatedMutationState targetState,
            String lastError,
            String degradationReason
    ) {
        SubmitDecisionOperationStatus publicStatus = publicStatusMapper.currentStatus(targetState);
        RegulatedMutationExecutionStatus executionStatus = targetState == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
                ? RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
                : RegulatedMutationExecutionStatus.COMPLETED;
        long resultingRevision = fencedCommandWriter.recoveryTransition(
                command,
                targetState,
                executionStatus,
                lastError,
                update -> {
                    update.set("public_status", publicStatus);
                    if (degradationReason != null) {
                        update.set("degradation_reason", degradationReason);
                    }
                }
        );
        command.setState(targetState);
        command.setExecutionStatus(executionStatus);
        command.setLeaseOwner(null);
        command.setLeaseExpiresAt(null);
        command.setPublicStatus(publicStatus);
        command.setLastError(lastError);
        if (degradationReason != null) {
            command.setDegradationReason(degradationReason);
        }
        command.setUpdatedAt(java.time.Instant.now());
        command.setRevision(resultingRevision);
    }

    public EvidenceDecision decision(RegulatedMutationCommandDocument command) {
        DurableLocalFinalizationProofResult proof = durableLocalFinalizationProof.verify(command);
        if (!proof.valid()) {
            return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, proof.reasonCode());
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
            EvidenceDecision publicationDecision = publicationConfirmationDecision(outbox);
            if (publicationDecision.outcome() != EvidenceConfirmationOutcome.CONFIRMED) {
                return publicationDecision;
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

    private EvidenceDecision publicationConfirmationDecision(TransactionalOutboxRecordDocument outbox) {
        if (outbox.getPublishedAt() == null) {
            return new EvidenceDecision(EvidenceConfirmationOutcome.FAILED, "PUBLICATION_TIMESTAMP_MISSING");
        }
        OutboxPublicationConfirmationProvenance provenance = outbox.getPublicationConfirmationProvenance();
        if (provenance == null) {
            return new EvidenceDecision(
                    EvidenceConfirmationOutcome.FAILED,
                    "PUBLICATION_CONFIRMATION_PROVENANCE_MISSING"
            );
        }
        return switch (provenance) {
            case BROKER_ACKNOWLEDGED -> OutboxAlertProjectionPolicy.brokerPublicationHasNoManualMetadata(outbox)
                    ? confirmedPublication()
                    : invalidManualPublicationEvidence();
            case MANUAL_DUAL_CONTROL_ATTESTED ->
                    OutboxAlertProjectionPolicy.validDualControlPublicationEvidence(outbox)
                    ? confirmedPublication()
                    : invalidManualPublicationEvidence();
            case MANUAL_SINGLE_CONTROL_ATTESTED ->
                    OutboxAlertProjectionPolicy.validSingleControlPublicationEvidence(outbox)
                            ? new EvidenceDecision(
                                    EvidenceConfirmationOutcome.PENDING,
                                    "MANUAL_PUBLICATION_REQUIRES_DUAL_CONTROL"
                            )
                            : invalidManualPublicationEvidence();
        };
    }

    private EvidenceDecision confirmedPublication() {
        return new EvidenceDecision(EvidenceConfirmationOutcome.CONFIRMED, null);
    }

    private EvidenceDecision invalidManualPublicationEvidence() {
        return new EvidenceDecision(
                EvidenceConfirmationOutcome.FAILED,
                "MANUAL_PUBLICATION_EVIDENCE_INVALID"
        );
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

    AlertStatusProjectionResult updateAlertOperationStatus(
            RegulatedMutationCommandDocument command,
            SubmitDecisionOperationStatus status
    ) {
        if (mongoTemplate == null || command.getResourceId() == null || command.getResourceId().isBlank()) {
            return AlertStatusProjectionResult.NOT_APPLICABLE;
        }
        if (!AuditAction.SUBMIT_ANALYST_DECISION.name().equals(command.getAction())
                || !AuditResourceType.ALERT.name().equals(command.getResourceType())) {
            return AlertStatusProjectionResult.NOT_APPLICABLE;
        }
        try {
            long revision = command.requireRevision();
            UpdateResult result = mongoTemplate.updateFirst(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(command.getResourceId()),
                            Criteria.where("decisionIdempotencyKey").is(command.getIdempotencyKey()),
                            new Criteria().orOperator(
                                    Criteria.where("decisionOperationRevision").exists(false),
                                    Criteria.where("decisionOperationRevision").is(null),
                                    Criteria.where("decisionOperationRevision").lt(revision)
                            )
                    )),
                    new Update()
                            .set("decisionOperationStatus", status.name())
                            .set("decisionOperationRevision", revision),
                    AlertDocument.class
            );
            if (result.getMatchedCount() == 0 && !alreadyProjectedAtSameOrNewerRevision(command, status, revision)) {
                metrics.recordRegulatedMutationAlertStatusProjection("FAILED", "TARGET_NOT_FOUND_OR_MISMATCH");
                throw new RegulatedMutationAlertProjectionException(
                        command.getId(),
                        "Alert operation status projection target was not found or did not match the command identity."
                );
            }
            return result.getMatchedCount() == 0
                    ? AlertStatusProjectionResult.ALREADY_CURRENT
                    : AlertStatusProjectionResult.UPDATED;
        } catch (DataAccessException exception) {
            metrics.recordRegulatedMutationAlertStatusProjection("FAILED", "DATA_ACCESS_ERROR");
            throw new RegulatedMutationAlertProjectionException(
                    command.getId(),
                    "Alert operation status projection failed.",
                    exception
            );
        }
    }

    private void recordSuccessfulProjection(AlertStatusProjectionResult result) {
        if (result != AlertStatusProjectionResult.NOT_APPLICABLE) {
            metrics.recordRegulatedMutationAlertStatusProjection("SUCCESS", result.name());
        }
    }

    private boolean alreadyProjectedAtSameOrNewerRevision(
            RegulatedMutationCommandDocument command,
            SubmitDecisionOperationStatus status,
            long revision
    ) {
        AlertDocument current = mongoTemplate.findById(command.getResourceId(), AlertDocument.class);
        if (current == null
                || !java.util.Objects.equals(current.getDecisionIdempotencyKey(), command.getIdempotencyKey())
                || current.getDecisionOperationRevision() == null
                || current.getDecisionOperationRevision() < revision) {
            return false;
        }
        return current.getDecisionOperationRevision() > revision
                || status.name().equals(current.getDecisionOperationStatus());
    }

    public enum EvidenceConfirmationOutcome {
        CONFIRMED,
        PENDING,
        FAILED
    }

    public record EvidenceDecision(EvidenceConfirmationOutcome outcome, String reason) {
    }

    enum AlertStatusProjectionResult {
        UPDATED,
        ALREADY_CURRENT,
        NOT_APPLICABLE
    }
}
