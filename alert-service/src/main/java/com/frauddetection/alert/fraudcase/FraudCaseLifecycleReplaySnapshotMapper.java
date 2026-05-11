package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.persistence.FraudCaseDocument;

import java.time.Instant;
import java.util.List;

public class FraudCaseLifecycleReplaySnapshotMapper {

    public FraudCaseLifecycleReplaySnapshot toSnapshot(
            FraudCaseLifecycleIdempotencyCommand command,
            Object response,
            Instant completedAt
    ) {
        if (response instanceof FraudCaseDocument document) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshotType.CASE,
                    command.action(),
                    document.getCaseId(),
                    document.getCaseNumber(),
                    document.getStatus(),
                    document.getPriority(),
                    document.getRiskLevel(),
                    document.getLinkedAlertIds() == null ? List.of() : List.copyOf(document.getLinkedAlertIds()),
                    document.getAssignedInvestigatorId(),
                    document.getCreatedBy(),
                    document.getReason(),
                    document.getCreatedAt(),
                    document.getUpdatedAt(),
                    completedAt,
                    document.getClosureReason(),
                    document.getClosedAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        if (response instanceof FraudCaseNoteResponse note) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshotType.NOTE,
                    command.action(),
                    note.caseId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    note.createdBy(),
                    null,
                    note.createdAt(),
                    null,
                    completedAt,
                    null,
                    null,
                    note.id(),
                    note.body(),
                    note.internalOnly(),
                    null,
                    null,
                    null
            );
        }
        if (response instanceof FraudCaseDecisionResponse decision) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshotType.DECISION,
                    command.action(),
                    decision.caseId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    decision.createdBy(),
                    null,
                    decision.createdAt(),
                    null,
                    completedAt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    decision.id(),
                    decision.decisionType(),
                    decision.summary()
            );
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T toResponse(FraudCaseLifecycleReplaySnapshot snapshot, Class<T> responseType) {
        if (responseType == FraudCaseDocument.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.CASE) {
            FraudCaseDocument document = new FraudCaseDocument();
            document.setCaseId(snapshot.caseId());
            document.setCaseNumber(snapshot.caseNumber());
            document.setStatus(snapshot.status());
            document.setPriority(snapshot.priority());
            document.setRiskLevel(snapshot.riskLevel());
            document.setLinkedAlertIds(snapshot.linkedAlertIds() == null ? List.of() : List.copyOf(snapshot.linkedAlertIds()));
            document.setAssignedInvestigatorId(snapshot.assignedTo());
            document.setCreatedBy(snapshot.createdBy());
            document.setReason(snapshot.reason());
            document.setCreatedAt(snapshot.createdAt());
            document.setUpdatedAt(snapshot.updatedAt());
            document.setClosureReason(snapshot.closureReason());
            document.setClosedAt(snapshot.closedAt());
            return (T) document;
        }
        if (responseType == FraudCaseNoteResponse.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.NOTE) {
            return (T) new FraudCaseNoteResponse(
                    snapshot.noteId(),
                    snapshot.caseId(),
                    snapshot.noteBody(),
                    snapshot.createdBy(),
                    snapshot.createdAt(),
                    Boolean.TRUE.equals(snapshot.noteInternalOnly())
            );
        }
        if (responseType == FraudCaseDecisionResponse.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.DECISION) {
            return (T) new FraudCaseDecisionResponse(
                    snapshot.decisionId(),
                    snapshot.caseId(),
                    snapshot.decisionType(),
                    snapshot.decisionSummary(),
                    snapshot.createdBy(),
                    snapshot.createdAt()
            );
        }
        throw new IllegalStateException("Unsupported fraud case lifecycle replay snapshot type.");
    }
}
