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
    void alertServiceDeserializesVersion2DeviceSummaryAndKeepsMlEvidenceOmissionOwnership() throws Exception {
        ObjectNode eventJson = (ObjectNode) objectMapper.readTree(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson()
        );
        ObjectNode summary = (ObjectNode) eventJson.path("engineIntelligence");
        summary.put("contractVersion", 2);
        summary.set("engines", objectMapper.readTree("""
                [
                  {"engineId":"rules.primary","engineType":"RULES","status":"AVAILABLE","riskLevel":"HIGH","scoreBucket":"HIGH","reasonCodes":["HIGH_VELOCITY"]},
                  {"engineId":"ml.python.primary","engineType":"ML_MODEL","status":"TIMEOUT","riskLevel":null,"scoreBucket":"UNAVAILABLE","reasonCodes":["ML_MODEL_TIMEOUT"]},
                  {"engineId":"velocity.primary","engineType":"VELOCITY","status":"SKIPPED","riskLevel":null,"scoreBucket":"UNAVAILABLE","reasonCodes":["VELOCITY_FEATURES_UNAVAILABLE"]},
                  {"engineId":"device.primary","engineType":"DEVICE_RISK","status":"DEGRADED","riskLevel":null,"scoreBucket":"UNAVAILABLE","reasonCodes":["DEVICE_SCORE_NOT_CALIBRATED"]}
                ]
                """));
        summary.set("comparison", objectMapper.readTree("""
                {"comparisonType":"RULES_VS_ML","comparedEngineIds":["rules.primary","ml.python.primary"],"agreementStatus":"PARTIAL","riskMismatchStatus":"NOT_COMPARABLE","scoreDeltaBucket":"UNAVAILABLE"}
                """));
        summary.set("diagnosticSignals", objectMapper.readTree("""
                [{"engineId":"device.primary","engineType":"DEVICE_RISK","engineStatus":"DEGRADED","signalCategory":"OPERATIONAL_SIGNAL","riskLevel":null,"scoreBucket":"UNAVAILABLE","reasonCode":"DEVICE_SCORE_NOT_CALIBRATED"}]
                """));

        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.deserializeJson(eventJson.toString());

        assertThat(event.engineIntelligence().contractVersion()).isEqualTo(2);
        assertThat(event.engineIntelligence().engines())
                .extracting(com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.mlPredictionEvidenceOmissionReason()).isEqualTo(
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE);
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
    void alertServiceRejectsOversizedNestedEngineIntelligenceBeforeTypedDeserialization() throws Exception {
        ObjectNode eventJson = (ObjectNode) objectMapper.readTree(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson()
        );
        ((ObjectNode) eventJson.path("engineIntelligence")).put("futureExtension", "x".repeat(17_000));

        assertThatThrownBy(() -> AlertServiceTransactionScoredEventFixtureLoader.deserializeJson(eventJson.toString()))
                .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class)
                .hasMessageContaining("Unable to deserialize Kafka payload");
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
        assertThat(event.eventId()).isEqualTo("evt-engine-intelligence-contract-001");
        assertThat(event.transactionId()).isEqualTo("txn-engine-intelligence-contract-001");
        assertThat(event.correlationId()).isEqualTo("corr-engine-intelligence-contract-001");
        assertThat(event.customerId()).isEqualTo("cust-engine-intelligence-contract-001");
        assertThat(event.alertRecommended()).isTrue();
    }
}
