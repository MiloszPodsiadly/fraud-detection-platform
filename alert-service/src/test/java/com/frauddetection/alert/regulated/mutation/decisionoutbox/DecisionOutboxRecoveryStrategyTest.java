package com.frauddetection.alert.regulated.mutation.decisionoutbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionOutboxRecoveryStrategyTest {

    @Test
    void laterAlertStateDoesNotBecomeTheOriginalOperationResponse() {
        AlertRepository repository = mock(AlertRepository.class);
        DecisionOutboxRecoveryStrategy strategy = new DecisionOutboxRecoveryStrategy(repository);
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setResourceId("alert-7");
        command.setAction(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION.name());
        command.setResourceType(AuditResourceType.DECISION_OUTBOX.name());

        AlertDocument laterState = new AlertDocument();
        laterState.setAlertId("alert-7");
        laterState.setDecisionOutboxStatus("PUBLISHED");
        laterState.setDecisionOutboxPublishedAt(Instant.parse("2026-09-28T09:00:00Z"));
        when(repository.findById("alert-7")).thenReturn(Optional.of(laterState));

        assertThat(strategy.validateBusinessState(command).valid()).isFalse();
        assertThat(strategy.validateBusinessState(command).reasonCode())
                .isEqualTo("IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE");
        assertThat(strategy.reconstructSnapshot(command)).isEmpty();
    }
}
