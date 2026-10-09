package com.frauddetection.scoring.mapper;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEventMapperEngineIntelligenceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-05-31T10:00:00Z");

    private final TransactionScoredEventMapper mapper = new TransactionScoredEventMapper();
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void mapperOmitsEngineIntelligenceWhenOptionalEmpty() throws Exception {
        var event = mapper.toEvent(
                request(), scoreResult(), Optional.empty(),
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED, null
        );

        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(objectMapper.writeValueAsString(event))
                .doesNotContain("\"engineIntelligence\"", "\"mlPredictionEvidence\"");
        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED);
    }

    @Test
    void currentMapperRequiresExactlyOneMlPredictionEvidenceOutcome() {
        assertThatThrownBy(() -> mapper.toEvent(
                request(),
                scoreResult(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
    }

    @Test
    void mapperIncludesEngineIntelligenceWhenProvided() throws Exception {
        var event = mapper.toEvent(
                request(), scoreResult(), Optional.of(summary()),
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE, null
        );

        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(objectMapper.writeValueAsString(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void mapperIncludesExactMlPredictionEvidenceFromSameEnrichment() throws Exception {
        MlModelIdentity identity = new MlModelIdentity(
                "python-logistic-fraud-model",
                "model-X",
                "2026-05-30.feature-contract.v1"
        );
        MlPredictionEvidence evidence = new MlPredictionEvidence(
                0.8123d,
                RiskLevel.HIGH,
                identity,
                "a".repeat(64),
                GENERATED_AT
        );

        var event = mapper.toEvent(
                request(),
                scoreResult(),
                Optional.of(availableMlSummary("model-X")),
                Optional.of(evidence),
                Optional.empty(),
                null
        );

        assertThat(event.mlPredictionEvidence()).isEqualTo(evidence);
        assertThat(objectMapper.writeValueAsString(event))
                .contains("\"mlPredictionEvidence\"", "\"mlScore\":0.8123", "\"modelArtifactSha256\"");
    }

    @Test
    void mapperDoesNotChangeExistingEventFieldsWhenEngineIntelligenceProvided() {
        var withoutSummary = mapper.toEvent(
                request(), scoreResult(), Optional.empty(),
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED, null
        );
        var withSummary = mapper.toEvent(
                request(), scoreResult(), Optional.of(summary()),
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE, null
        );

        assertThat(withSummary)
                .usingRecursiveComparison()
                .ignoringFields(
                        "eventId",
                        "createdAt",
                        "engineIntelligence",
                        "mlPredictionEvidenceOmissionReason"
                )
                .isEqualTo(withoutSummary);
    }

    @Test
    void shadowModeMlIdentityStaysInsideEngineIntelligenceNotTopLevelFinalScoreIdentity() throws Exception {
        var event = mapper.toEvent(
                request(),
                ruleBasedScoreResult("rules-v2-final"),
                Optional.of(availableMlSummary("model-X")),
                Optional.of(new MlPredictionEvidence(
                        0.8123d,
                        RiskLevel.HIGH,
                        new MlModelIdentity(
                                "python-logistic-fraud-model",
                                "model-X",
                                "2026-05-30.feature-contract.v1"
                        ),
                        "a".repeat(64),
                        GENERATED_AT
                )),
                Optional.empty(),
                null
        );

        assertThat(event.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(event.modelVersion()).isEqualTo("rules-v2-final");
        assertThat(event.engineIntelligence().engines().get(1).modelIdentity().modelVersion())
                .isEqualTo("model-X");

        var serialized = objectMapper.readTree(objectMapper.writeValueAsString(event));
        assertThat(serialized.get("scoringStrategy").asText()).isEqualTo("RULE_BASED");
        assertThat(serialized.get("modelVersion").asText()).isEqualTo("rules-v2-final");
        assertThat(serialized.get("engineIntelligence").get("engines").get(1)
                .get("modelIdentity").get("modelVersion").asText()).isEqualTo("model-X");
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    private FraudScoreResult scoreResult() {
        return ruleBasedScoreResult("v1");
    }

    private FraudScoreResult ruleBasedScoreResult(String modelVersion) {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                modelVersion,
                GENERATED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of("finalScore", 0.91d),
                Map.of("transactionAmount", 100.0d),
                Map.of(),
                true
        );
    }

    private EngineIntelligenceSummary summary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.TIMEOUT,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_TIMEOUT")
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    private EngineIntelligenceSummary availableMlSummary(String mlModelVersion) {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.VERY_HIGH,
                                List.of("MODEL_HIGH_RISK"),
                                new com.frauddetection.common.events.intelligence.MlModelIdentity(
                                        "python-logistic-fraud-model",
                                        mlModelVersion,
                                        "2026-05-30.feature-contract.v1"
                                )
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.SMALL
                ),
                List.of(),
                List.of()
        );
    }
}
