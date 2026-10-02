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
    void mutableTransactionalOutboxRecordIsNotUsedToReconstructMissingSnapshot() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecoveryStrategy strategy = new TransactionalOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = command("event-7");
        TransactionalOutboxRecordDocument record = record("event-7");
        when(repository.findById("event-7")).thenReturn(Optional.of(record));

        assertThat(strategy.supports(
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX
        )).isTrue();
        assertThat(strategy.reconstructSnapshot(command)).isEmpty();
        assertThat(strategy.validateBusinessState(command).valid()).isFalse();
        assertThat(strategy.validateBusinessState(command).reasonCode())
                .isEqualTo("IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void missingTransactionalOutboxRecordRequiresRecovery() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecoveryStrategy strategy = new TransactionalOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = command("event-missing");
        when(repository.findById("event-missing")).thenReturn(Optional.empty());

        RecoveryValidationResult result = strategy.validateBusinessState(command);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE");
        assertThat(strategy.reconstructSnapshot(command)).isEmpty();
    }

    @Test
    void laterOutboxStateDoesNotBecomeTheOriginalOperationResponse() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecoveryStrategy strategy = new TransactionalOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = command("event-7");
        TransactionalOutboxRecordDocument laterState = record("event-7");
        laterState.setStatus(com.frauddetection.alert.outbox.TransactionalOutboxStatus.PUBLISHED);
        laterState.setPublishedAt(Instant.parse("2026-09-28T09:00:00Z"));
        when(repository.findById("event-7")).thenReturn(Optional.of(laterState));

        assertThat(strategy.validateBusinessState(command).valid()).isFalse();
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
