package com.frauddetection.alert.consumer;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void alertServiceDeserializesLegacyV1EngineIntelligenceComparison() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.legacyV1EngineIntelligence();

        assertThat(event.transactionId()).isEqualTo("txn-fdp129-stage2-001");
        assertThat(event.engineIntelligence().comparison().comparisonType().name()).isEqualTo("RULES_VS_ML");
        assertThat(event.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
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

    @Test
    void alertServiceRejectsPartialComparisonIdentity() {
        assertThatThrownBy(AlertServiceTransactionScoredEventFixtureLoader::partialComparisonTypeOnly)
                .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class)
                .hasMessageContaining("Unable to deserialize Kafka payload");
    }

    private void assertExistingFields(TransactionScoredEvent event) {
        assertThat(event.eventId()).isEqualTo("evt-fdp93-001");
        assertThat(event.transactionId()).isEqualTo("txn-fdp93-001");
        assertThat(event.correlationId()).isEqualTo("corr-fdp93-001");
        assertThat(event.customerId()).isEqualTo("cust-fdp93-001");
        assertThat(event.alertRecommended()).isTrue();
    }
}
