package com.frauddetection.alert.consumer;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertServiceTransactionScoredEventDeserializationCompatibilityTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void alertServiceDeserializesCurrentEventWithoutEngineIntelligence() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.withoutEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.modelVersion()).isEqualTo("v2");
        assertThat(event.engineIntelligence()).isNull();
    }

    @Test
    void alertServiceDeserializesMinimalEngineIntelligenceEvent() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.modelVersion()).isEqualTo("v2");
        assertThat(event.engineIntelligence()).isNotNull();
    }

    @Test
    void alertServiceRejectsHistoricalComparisonNormalization() throws Exception {
        ObjectNode eventJson = (ObjectNode) objectMapper.readTree(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson()
        );
        eventJson.put("modelVersion", "v1");
        ObjectNode comparison = (ObjectNode) eventJson.path("engineIntelligence").path("comparison");
        comparison.remove("comparisonType");
        comparison.remove("comparedEngineIds");

        assertThatThrownBy(() -> AlertServiceTransactionScoredEventFixtureLoader.deserializeJson(eventJson.toString()))
                .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class)
                .hasMessageContaining("Unable to deserialize Kafka payload");
    }

    @Test
    void alertServiceDeserializesFullBoundedEngineIntelligenceEvent() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence();

        assertExistingFields(event);
        assertThat(event.modelVersion()).isEqualTo("v2");
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
    void alertServiceRejectsPartialComparisonIdentity() throws Exception {
        ObjectNode eventJson = (ObjectNode) objectMapper.readTree(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson()
        );
        ((ObjectNode) eventJson.path("engineIntelligence").path("comparison")).remove("comparedEngineIds");

        assertThatThrownBy(() -> AlertServiceTransactionScoredEventFixtureLoader.deserializeJson(eventJson.toString()))
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
