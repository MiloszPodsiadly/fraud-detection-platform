package com.frauddetection.alert.outbox.recovery;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxProjectionRepairPolicyTest {

    @Test
    void interleavesScheduledAndUnscheduledCandidatesWithoutDuplicates() {
        TransactionalOutboxRecordDocument scheduledFirst = record("scheduled-first", "alert-1", 1L);
        TransactionalOutboxRecordDocument duplicate = record("duplicate", "alert-2", 1L);
        TransactionalOutboxRecordDocument unscheduledFirst = record("unscheduled-first", "alert-3", 1L);

        assertThat(OutboxProjectionRepairPolicy.fairCandidates(
                List.of(scheduledFirst, duplicate),
                List.of(unscheduledFirst, duplicate),
                3
        )).extracting(TransactionalOutboxRecordDocument::getEventId)
                .containsExactly("scheduled-first", "unscheduled-first", "duplicate");
    }

    @Test
    void explainsEveryReadOnlyProjectionRejection() {
        TransactionalOutboxRecordDocument record = record("event-1", "alert-1", 2L);
        AlertDocument matching = alert("event-1", 2L);

        assertThat(OutboxProjectionRepairPolicy.rejectionReason(record(null, null, 2L), matching))
                .contains("ALERT_PROJECTION_RESOURCE_ID_MISSING");
        assertThat(OutboxProjectionRepairPolicy.rejectionReason(record, null))
                .contains("ALERT_PROJECTION_NOT_FOUND");
        assertThat(OutboxProjectionRepairPolicy.rejectionReason(record, alert("other-event", 2L)))
                .contains("ALERT_PROJECTION_EVENT_MISMATCH");
        assertThat(OutboxProjectionRepairPolicy.rejectionReason(record, alert("event-1", 3L)))
                .contains("ALERT_PROJECTION_NEWER_THAN_SOURCE");
        assertThat(OutboxProjectionRepairPolicy.rejectionReason(record, matching)).isEmpty();
    }

    private TransactionalOutboxRecordDocument record(String eventId, String resourceId, long revision) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setResourceId(resourceId);
        record.setProjectionRevision(revision);
        return record;
    }

    private AlertDocument alert(String eventId, long revision) {
        AlertDocument alert = new AlertDocument();
        alert.setDecisionOutboxEventId(eventId);
        alert.setDecisionOutboxProjectionRevision(revision);
        return alert;
    }
}
