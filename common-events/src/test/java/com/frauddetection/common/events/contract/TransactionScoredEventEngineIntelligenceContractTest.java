package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEventEngineIntelligenceContractTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final JacksonKafkaDeserializer<TransactionScoredEvent> kafkaDeserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void currentEventWithoutEngineIntelligenceIsAccepted() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.withoutEngineIntelligenceJson());

        assertThat(event.modelVersion()).isEqualTo("v2");
        assertThat(event.engineIntelligence()).isNull();
    }

    @Test
    void currentEventWithAbsentEngineIntelligenceAndNullModelLineageIsAccepted() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.withoutEngineIntelligenceJson());
        eventJson.putNull("modelName");
        eventJson.putNull("modelVersion");
        eventJson.putNull("inferenceTimestamp");

        TransactionScoredEvent event = read(eventJson.toString());

        assertThat(event.modelName()).isNull();
        assertThat(event.modelVersion()).isNull();
        assertThat(event.inferenceTimestamp()).isNull();
        assertThat(event.engineIntelligence()).isNull();
    }

    @Test
    void currentComparisonWithExplicitIdentityIsAccepted() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());

        assertThat(event.engineIntelligence().comparison().comparisonType())
                .isEqualTo(EngineIntelligenceComparisonType.RULES_VS_ML);
        assertThat(event.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(event.engineIntelligence().engines().get(1).modelIdentity()).isNull();
    }

    @Test
    void historicalComparisonNormalizationIsRejectedEvenWhenOuterModelVersionIsV1() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        eventJson.put("modelVersion", "v1");
        comparison(eventJson).remove("comparisonType");
        comparison(eventJson).remove("comparedEngineIds");

        assertThatThrownBy(() -> read(eventJson.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasMessageContaining("comparisonType is required");
    }

    @Test
    void kafkaReplayDoesNotNormalizeHistoricalComparisonIdentity() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        eventJson.put("modelVersion", "v1");
        comparison(eventJson).remove("comparisonType");
        comparison(eventJson).remove("comparedEngineIds");

        assertThatThrownBy(() -> kafkaDeserializer.deserialize(
                "transactions.scored",
                eventJson.toString().getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasRootCauseMessage("comparisonType is required");
    }

    @Test
    void incompleteComparisonIdentityIsRejected() throws Exception {
        ObjectNode withoutIds = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        comparison(withoutIds).remove("comparedEngineIds");
        ObjectNode withoutType = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        comparison(withoutType).remove("comparisonType");

        assertThatThrownBy(() -> read(withoutIds.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasMessageContaining("comparedEngineIds is required");
        assertThatThrownBy(() -> read(withoutType.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasMessageContaining("comparisonType is required");
    }

    @Test
    void unsupportedComparisonEngineIdsAreRejected() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        ArrayNode comparedEngineIds = objectMapper.createArrayNode();
        comparedEngineIds.add("rules.primary");
        comparedEngineIds.add("unknown.primary");
        comparison(eventJson).set("comparedEngineIds", comparedEngineIds);

        assertThatThrownBy(() -> read(eventJson.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_COMPARISON_ENGINE_IDS_INVALID");
    }

    @Test
    void incorrectEngineOrderingIsRejected() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        ArrayNode engines = (ArrayNode) eventJson.path("engineIntelligence").path("engines");
        JsonNode rules = engines.get(0);
        JsonNode ml = engines.get(1);
        engines.removeAll();
        engines.add(ml);
        engines.add(rules);

        assertThatThrownBy(() -> read(eventJson.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_ENGINE_ORDER_INVALID");
    }

    @Test
    void invalidComparisonSemanticsAreRejected() throws Exception {
        ObjectNode eventJson = eventJson(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson());
        comparison(eventJson).put("agreementStatus", "AGREEMENT");

        assertThatThrownBy(() -> read(eventJson.toString()))
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
    }

    private TransactionScoredEvent read(String json) throws Exception {
        return objectMapper.readValue(json, TransactionScoredEvent.class);
    }

    private ObjectNode eventJson(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    private ObjectNode comparison(ObjectNode eventJson) {
        return (ObjectNode) eventJson.path("engineIntelligence").path("comparison");
    }
}
