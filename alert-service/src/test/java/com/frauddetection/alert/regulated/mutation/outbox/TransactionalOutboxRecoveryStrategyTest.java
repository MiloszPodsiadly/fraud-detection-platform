package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.regulated.RecoveryValidationResult;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionalOutboxRecoveryStrategyTest {

    @Test
    void reconstructsOnlyTheTransactionalOutboxRecordSelectedByResourceId() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecoveryStrategy strategy = new TransactionalOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = command("event-7");
        TransactionalOutboxRecordDocument record = record("event-7");
        when(repository.findById("event-7")).thenReturn(Optional.of(record));

        assertThat(strategy.supports(
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX
        )).isTrue();
        assertThat(strategy.supports(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX
        )).isFalse();
        assertThat(strategy.reconstructSnapshot(command)).isPresent();
        assertThat(strategy.validateBusinessState(command).valid()).isTrue();
    }

    @Test
    void missingTransactionalOutboxRecordRequiresRecovery() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecoveryStrategy strategy = new TransactionalOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = command("event-missing");
        when(repository.findById("event-missing")).thenReturn(Optional.empty());

        RecoveryValidationResult result = strategy.validateBusinessState(command);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("BUSINESS_STATE_NOT_RECONSTRUCTABLE");
        assertThat(strategy.reconstructSnapshot(command)).isEmpty();
    }

    private static RegulatedMutationCommandDocument command(String resourceId) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setResourceId(resourceId);
        command.setAction(AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION.name());
        command.setResourceType(AuditResourceType.DECISION_OUTBOX.name());
        return command;
    }

    private static TransactionalOutboxRecordDocument record(String eventId) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setResourceId("alert-7");
        record.setResourceType("ALERT");
        record.setEventType("FraudDecisionConfirmed");
        record.setCreatedAt(Instant.parse("2026-09-28T08:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-09-28T08:00:00Z"));
        return record;
    }
}
