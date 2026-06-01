package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEventFixtureCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void oldFixtureDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.oldWithoutEngineIntelligenceJson()).engineIntelligence()).isNull();
    }

    @Test
    void minimalEngineIntelligenceFixtureDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson()).engineIntelligence()).isNotNull();
    }

    @Test
    void fullBoundedEngineIntelligenceFixtureDeserializes() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson());

        assertThat(event.engineIntelligence().engines()).hasSize(2);
        assertThat(event.engineIntelligence().diagnosticSignals()).hasSize(2);
    }

    @Test
    void unknownNestedEngineIntelligenceFixtureDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFieldsJson()).engineIntelligence())
                .isNotNull();
    }

    @Test
    void unknownTopLevelFieldFixtureDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.unknownTopLevelFieldJson()).transactionId())
                .isEqualTo("txn-fdp93-001");
    }

    @Test
    void fixturesDoNotContainForbiddenDecisioningFields() throws Exception {
        assertEngineIntelligenceDoesNotContain(
                "finalDecision", "recommendedAction", "approve", "decline", "block",
                "platformRiskScore", "platformRiskLevel", "paymentDecision", "authorizationDecision"
        );
    }

    @Test
    void fixturesDoNotContainRawEvidenceOrSecrets() throws Exception {
        assertEngineIntelligenceDoesNotContain(
                "rawPayload", "rawEvidence", "rawContribution", "endpoint", "token",
                "secret", "password", "stackTrace", "exception"
        );
    }

    @Test
    void fixtureLoaderReadsAllFixtures() {
        assertThat(List.of(
                TransactionScoredEventFixtureLoader.oldWithoutEngineIntelligenceJson(),
                TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson(),
                TransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson(),
                TransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFieldsJson(),
                TransactionScoredEventFixtureLoader.unknownTopLevelFieldJson()
        )).allMatch(json -> !json.isBlank());
    }

    @Test
    void fixtureLoaderRejectsUnknownFixtureName() {
        assertThatThrownBy(() -> TransactionScoredEventFixtureLoader.readFixture("not-reviewed.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TRANSACTION_SCORED_EVENT_FIXTURE_UNKNOWN");
    }

    private TransactionScoredEvent read(String json) throws Exception {
        return objectMapper.readValue(json, TransactionScoredEvent.class);
    }

    private void assertEngineIntelligenceDoesNotContain(String... forbidden) throws Exception {
        for (String fixture : List.of(
                TransactionScoredEventFixtureLoader.minimalEngineIntelligenceJson(),
                TransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson(),
                TransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFieldsJson(),
                TransactionScoredEventFixtureLoader.unknownTopLevelFieldJson()
        )) {
            JsonNode engineIntelligence = objectMapper.readTree(fixture).path("engineIntelligence");
            assertThat(engineIntelligence.toString()).doesNotContainIgnoringCase(forbidden);
        }
    }
}
