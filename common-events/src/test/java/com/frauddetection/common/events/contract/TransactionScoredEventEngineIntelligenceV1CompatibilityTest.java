package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEventEngineIntelligenceV1CompatibilityTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final JacksonKafkaDeserializer<TransactionScoredEvent> kafkaDeserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void oldEventWithoutEngineIntelligenceStillDeserializes() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.oldWithoutEngineIntelligenceJson());

        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.fraudScore()).isEqualTo(0.82d);
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void legacyV1ComparisonWithoutIdentityDeserializesAndNormalizesIdentity() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.legacyV1EngineIntelligenceJson());

        assertThat(event.engineIntelligence().comparison().comparisonType())
                .isEqualTo(EngineIntelligenceComparisonType.RULES_VS_ML);
        assertThat(event.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(event.engineIntelligence().comparison().agreementStatus())
                .isEqualTo(EngineIntelligenceAgreementStatus.AGREEMENT);
        assertThat(event.engineIntelligence().comparison().riskMismatchStatus())
                .isEqualTo(EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL);
        assertThat(event.engineIntelligence().comparison().scoreDeltaBucket())
                .isEqualTo(EngineIntelligenceScoreDeltaBucket.SMALL);
        assertThat(event.fraudScore()).isEqualTo(0.82d);
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void legacyV1ReplayDeserializesSameBytesAfterNewConsumerIsInstalled() {
        byte[] legacyBytes = TransactionScoredEventFixtureLoader.legacyV1EngineIntelligenceJson()
                .getBytes(StandardCharsets.UTF_8);

        TransactionScoredEvent firstReplay = kafkaDeserializer.deserialize("transactions.scored", legacyBytes);
        TransactionScoredEvent secondReplay = kafkaDeserializer.deserialize("transactions.scored", legacyBytes);

        assertThat(firstReplay.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(secondReplay.engineIntelligence().comparison())
                .isEqualTo(firstReplay.engineIntelligence().comparison());
        assertThat(secondReplay.fraudScore()).isEqualTo(firstReplay.fraudScore());
        assertThat(secondReplay.riskLevel()).isEqualTo(firstReplay.riskLevel());
    }

    @Test
    void currentComparisonWithExplicitIdentityDeserializes() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.explicitV1EngineIntelligenceJson());

        assertThat(event.engineIntelligence().comparison().comparisonType())
                .isEqualTo(EngineIntelligenceComparisonType.RULES_VS_ML);
        assertThat(event.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void currentComparisonUnknownAdditiveFieldsFollowExistingIgnorePolicy() throws Exception {
        TransactionScoredEvent event = read(TransactionScoredEventFixtureLoader.unknownAdditiveV1EngineIntelligenceJson());

        assertThat(event.transactionId()).isEqualTo("txn-fdp129-stage2-001");
        assertThat(event.engineIntelligence().engines()).hasSize(2);
        assertThat(event.engineIntelligence().comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void partialComparisonIdentityIsRejectedInsteadOfSilentlyCompleted() {
        Map<String, String> invalidFixtures = Map.of(
                "comparisonTypeOnly", TransactionScoredEventFixtureLoader.partialComparisonTypeOnlyJson(),
                "comparedEngineIdsOnly", TransactionScoredEventFixtureLoader.partialComparedEngineIdsOnlyJson()
        );

        invalidFixtures.forEach((caseName, json) -> assertThatThrownBy(() -> read(json))
                .as(caseName)
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_COMPARISON_IDENTITY_INCOMPLETE"));
    }

    @Test
    void wrongReversedVelocityOrUnknownComparisonIdentityIsRejected() {
        Map<String, String> invalidFixtures = Map.of(
                "wrongType", TransactionScoredEventFixtureLoader.wrongComparisonTypeJson(),
                "reversedIds", TransactionScoredEventFixtureLoader.reversedComparisonEngineIdsJson(),
                "velocityIds", TransactionScoredEventFixtureLoader.velocityComparisonEngineIdsJson(),
                "unknownIds", TransactionScoredEventFixtureLoader.unknownComparisonEngineIdsJson()
        );

        invalidFixtures.forEach((caseName, json) -> assertThatThrownBy(() -> read(json))
                .as(caseName)
                .isInstanceOf(Exception.class));
    }

    private TransactionScoredEvent read(String json) throws Exception {
        return objectMapper.readValue(json, TransactionScoredEvent.class);
    }

}
