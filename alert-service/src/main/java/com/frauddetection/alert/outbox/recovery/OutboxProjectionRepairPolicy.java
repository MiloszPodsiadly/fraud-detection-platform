package com.frauddetection.alert.outbox.recovery;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.persistence.AlertDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OutboxProjectionRepairPolicy {

    private OutboxProjectionRepairPolicy() {
    }

    public static List<TransactionalOutboxRecordDocument> fairCandidates(
            List<TransactionalOutboxRecordDocument> scheduled,
            List<TransactionalOutboxRecordDocument> unscheduled,
            int limit
    ) {
        List<TransactionalOutboxRecordDocument> safeScheduled = scheduled == null ? List.of() : scheduled;
        List<TransactionalOutboxRecordDocument> safeUnscheduled = unscheduled == null ? List.of() : unscheduled;
        Map<String, TransactionalOutboxRecordDocument> candidates = new LinkedHashMap<>();
        int index = 0;
        while (candidates.size() < limit
                && (index < safeScheduled.size() || index < safeUnscheduled.size())) {
            addIfAvailable(candidates, safeScheduled, index, limit);
            addIfAvailable(candidates, safeUnscheduled, index, limit);
            index++;
        }
        return List.copyOf(new ArrayList<>(candidates.values()));
    }

    public static Optional<String> rejectionReason(
            TransactionalOutboxRecordDocument record,
            AlertDocument alert
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return Optional.of("ALERT_PROJECTION_RESOURCE_ID_MISSING");
        }
        if (alert == null) {
            return Optional.of("ALERT_PROJECTION_NOT_FOUND");
        }
        if (!record.getEventId().equals(alert.getDecisionOutboxEventId())) {
            return Optional.of("ALERT_PROJECTION_EVENT_MISMATCH");
        }
        if (alert.getDecisionOutboxProjectionRevision() > record.getProjectionRevision()) {
            return Optional.of("ALERT_PROJECTION_NEWER_THAN_SOURCE");
        }
        return Optional.empty();
    }

    private static void addIfAvailable(
            Map<String, TransactionalOutboxRecordDocument> candidates,
            List<TransactionalOutboxRecordDocument> records,
            int index,
            int limit
    ) {
        if (candidates.size() < limit && index < records.size()) {
            TransactionalOutboxRecordDocument record = records.get(index);
            candidates.putIfAbsent(record.getEventId(), record);
        }
    }
}
