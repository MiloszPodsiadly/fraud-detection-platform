package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MutationEvidenceConfirmationService {

    private final RegulatedMutationCommandRepository commandRepository;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final AlertServiceMetrics metrics;
    private final boolean externalAnchorRequired;
    private final boolean signatureRequired;

    public MutationEvidenceConfirmationService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            AlertServiceMetrics metrics,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean externalAnchorRequired,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signatureRequired
    ) {
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.metrics = metrics;
        this.externalAnchorRequired = externalAnchorRequired;
        this.signatureRequired = signatureRequired;
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
                promoted++;
            } else if (decision.state() == RegulatedMutationState.COMMITTED_DEGRADED) {
                command.setState(RegulatedMutationState.COMMITTED_DEGRADED);
                command.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
                command.setDegradationReason(decision.reason());
                command.setLastError(decision.reason());
                command.setUpdatedAt(java.time.Instant.now());
                commandRepository.save(command);
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
            return new EvidenceDecision(RegulatedMutationState.EVIDENCE_PENDING, "EXTERNAL_ANCHOR_MISSING");
        }
        if (signatureRequired) {
            return new EvidenceDecision(RegulatedMutationState.EVIDENCE_PENDING, "SIGNATURE_UNAVAILABLE");
        }
        return new EvidenceDecision(RegulatedMutationState.EVIDENCE_CONFIRMED, null);
    }

    public record EvidenceDecision(RegulatedMutationState state, String reason) {
    }
}
