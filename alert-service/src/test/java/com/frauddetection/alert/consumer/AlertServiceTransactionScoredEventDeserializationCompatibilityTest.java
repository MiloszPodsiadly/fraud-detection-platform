package com.frauddetection.alert.consumer;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceTransactionScoredEventDeserializationCompatibilityTest {

    @Test
    void alertServiceDeserializesOldEventWithoutEngineIntelligence() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.oldWithoutEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.engineIntelligence()).isNull();
    }

    @Test
    void alertServiceDeserializesMinimalEngineIntelligenceEvent() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.engineIntelligence()).isNotNull();
    }

    @Test
    void alertServiceDeserializesFullBoundedEngineIntelligenceEvent() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.engineIntelligence().engines()).hasSize(2);
    }

    @Test
    void alertServiceDeserializesUnknownNestedEngineIntelligenceFields() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFields();

        assertExistingFields(event);
        assertThat(event.engineIntelligence()).isNotNull();
    }

    @Test
    void alertServiceDeserializesUnknownTopLevelFieldIfCurrentConsumerObjectMapperSupportsIt() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.unknownTopLevelField();

        assertExistingFields(event);
        assertThat(event.engineIntelligence()).isNotNull();
    }

    private void assertExistingFields(TransactionScoredEvent event) {
        assertThat(event.eventId()).isEqualTo("evt-fdp93-001");
        assertThat(event.transactionId()).isEqualTo("txn-fdp93-001");
        assertThat(event.correlationId()).isEqualTo("corr-fdp93-001");
        assertThat(event.customerId()).isEqualTo("cust-fdp93-001");
        assertThat(event.alertRecommended()).isTrue();
    }
}
