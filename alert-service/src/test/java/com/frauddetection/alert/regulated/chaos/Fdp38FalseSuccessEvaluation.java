package com.frauddetection.alert.regulated.chaos;

import java.util.List;

public record Fdp38FalseSuccessEvaluation(
        boolean publicSuccessStatusAbsent,
        boolean committedSnapshotAbsentWhenNotAllowed,
        boolean finalizedStatusAbsentWhenNotAllowed,
        boolean successAuditAbsentWhenNotAllowed,
        boolean outboxAbsentWhenNotAllowed,
        boolean businessMutationAbsentWhenNotAllowed,
        boolean duplicateMutationAbsent,
        boolean duplicateOutboxAbsent,
        boolean duplicateSuccessAuditAbsent,
        List<String> failedReasons
) {
    public boolean passed() {
        return failedReasons.isEmpty();
    }
}
