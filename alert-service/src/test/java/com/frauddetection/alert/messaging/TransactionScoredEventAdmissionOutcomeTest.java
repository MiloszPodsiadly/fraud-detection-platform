package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.annotation.Transactional;

import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEWER;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.CONFLICT_REJECTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.IDEMPOTENT_REPLAY;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.STALE_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionScoredEventAdmissionOutcomeTest {

    @ParameterizedTest
    @EnumSource(value = ScoringOccurrenceAdmissionResult.Outcome.class, names = {
            "APPLIED_NEW", "APPLIED_NEWER", "IDEMPOTENT_REPLAY"
    })
    void currentOccurrenceOutcomesApplyDependentEffects(ScoringOccurrenceAdmissionResult.Outcome outcome) {
        Fixture fixture = fixture(outcome);

        fixture.listener().onMessage(fixture.event(), null);

        verify(fixture.alertManagement()).handleScoredTransaction(fixture.event());
    }

    @Test
    void staleOccurrenceDoesNotApplyDependentEffects() {
        Fixture fixture = fixture(STALE_REJECTED);

        fixture.listener().onMessage(fixture.event(), null);

        verify(fixture.alertManagement(), never()).handleScoredTransaction(fixture.event());
    }

    @Test
    void conflictingOccurrenceFailsClosedBeforeDependentEffects() {
        Fixture fixture = fixture(CONFLICT_REJECTED);

        assertThatThrownBy(() -> fixture.listener().onMessage(fixture.event(), null))
                .isInstanceOf(ScoringOccurrenceConflictException.class)
                .hasMessage("SCORING_OCCURRENCE_CONFLICT");
        verify(fixture.alertManagement(), never()).handleScoredTransaction(fixture.event());
    }

    @Test
    void listenerOwnsOneRequiredMongoTransactionBoundary() throws NoSuchMethodException {
        Transactional transactional = TransactionScoredEventListener.class
                .getMethod("onMessage", TransactionScoredEvent.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.transactionManager()).isEqualTo("mongoTransactionManager");
    }

    private Fixture fixture(ScoringOccurrenceAdmissionResult.Outcome outcome) {
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction().build();
        AlertManagementUseCase alertManagement = mock(AlertManagementUseCase.class);
        TransactionMonitoringUseCase monitoring = mock(TransactionMonitoringUseCase.class);
        when(monitoring.recordScoredTransaction(event)).thenReturn(new ScoringOccurrenceAdmissionResult(
                outcome,
                reason(outcome)
        ));
        return new Fixture(
                new TransactionScoredEventListener(
                        alertManagement,
                        monitoring,
                        new KafkaTopicProperties(
                                "transactions.scored",
                                "fraud.alerts",
                                "fraud.decisions",
                                "transactions.dead-letter"
                        )
                ),
                alertManagement,
                event
        );
    }

    private ScoringOccurrenceAdmissionResult.ReasonCode reason(
            ScoringOccurrenceAdmissionResult.Outcome outcome
    ) {
        return switch (outcome) {
            case APPLIED_NEW -> ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED;
            case APPLIED_NEWER -> ScoringOccurrenceAdmissionResult.ReasonCode.NEWER_OCCURRENCE_ACCEPTED;
            case IDEMPOTENT_REPLAY -> ScoringOccurrenceAdmissionResult.ReasonCode.IDENTICAL_OCCURRENCE_REPLAYED;
            case STALE_REJECTED -> ScoringOccurrenceAdmissionResult.ReasonCode.OLDER_OCCURRENCE_REJECTED;
            case CONFLICT_REJECTED -> ScoringOccurrenceAdmissionResult.ReasonCode.OCCURRENCE_PAYLOAD_CONFLICT;
        };
    }

    private record Fixture(
            TransactionScoredEventListener listener,
            AlertManagementUseCase alertManagement,
            TransactionScoredEvent event
    ) {
    }
}
