package com.frauddetection.scoring.mapper;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventMapperEngineIntelligenceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-05-31T10:00:00Z");

    private final TransactionScoredEventMapper mapper = new TransactionScoredEventMapper();
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void mapperOmitsEngineIntelligenceWhenOptionalEmpty() throws Exception {
        var event = mapper.toEvent(request(), scoreResult(), Optional.empty());

        assertThat(event.engineIntelligence()).isNull();
        assertThat(objectMapper.writeValueAsString(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void mapperIncludesEngineIntelligenceWhenProvided() throws Exception {
        var event = mapper.toEvent(request(), scoreResult(), Optional.of(summary()));

        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(objectMapper.writeValueAsString(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void mapperDoesNotChangeExistingEventFieldsWhenEngineIntelligenceProvided() {
        var withoutSummary = mapper.toEvent(request(), scoreResult(), Optional.empty());
        var withSummary = mapper.toEvent(request(), scoreResult(), Optional.of(summary()));

        assertThat(withSummary)
                .usingRecursiveComparison()
                .ignoringFields("eventId", "createdAt", "engineIntelligence")
                .isEqualTo(withoutSummary);
    }

    @Test
    void shadowModeMlIdentityStaysInsideEngineIntelligenceNotTopLevelFinalScoreIdentity() {
        var event = mapper.toEvent(
                request(),
                ruleBasedScoreResult("rules-v2-final"),
                Optional.of(availableMlSummary("ml-shadow-2026-06-01"))
        );

        assertThat(event.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(event.modelVersion()).isEqualTo("rules-v2-final");
        assertThat(event.engineIntelligence().engines().get(1).modelIdentity().modelVersion())
                .isEqualTo("ml-shadow-2026-06-01");
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
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("MODEL_HIGH_RISK"),
                                new com.frauddetection.common.events.intelligence.MlModelIdentity(
                                        "python-logistic-fraud-model",
                                        mlModelVersion,
                                        "2026-05-30.feature-contract.v1"
                                )
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.NONE
                ),
                List.of(),
                List.of()
        );
    }
}
