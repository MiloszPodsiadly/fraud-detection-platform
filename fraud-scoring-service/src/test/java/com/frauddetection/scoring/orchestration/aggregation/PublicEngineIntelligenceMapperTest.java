package com.frauddetection.scoring.orchestration.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicEngineIntelligenceMapperTest {
    private final FraudEngineAggregationService service =
            new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy());
    private final PublicEngineIntelligenceMapper mapper = new PublicEngineIntelligenceMapper();

    @Test
    void mapsAgreementWithoutDecisioning() {
        assertThat(map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.HIGH).comparison().agreementStatus())
                .isEqualTo(EngineIntelligenceAgreementStatus.AGREEMENT);
    }

    @Test
    void mapsAdjacentVarianceWithoutDecisioning() {
        var summary = map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.MEDIUM);

        assertThat(summary.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.ADJACENT_RISK_VARIANCE);
        assertThat(summary.comparison().riskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.ADJACENT_RISK_LEVEL);
    }

    @Test
    void mapsDisagreementWithoutDecline() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(map(0.9d, RiskLevel.CRITICAL, 0.1d, RiskLevel.LOW));

        assertThat(json).contains("DISAGREEMENT").doesNotContainIgnoringCase("decline", "approve");
    }

    @Test
    void mapsTimeoutAsUnavailableScoreBucket() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable(
                        "ml.python.primary",
                        FraudEngineStatus.TIMEOUT,
                        "ORCHESTRATOR_ENGINE_TIMEOUT"
                )
        )));

        assertThat(summary.engines().get(1).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void mapsMissingScoreAsUnavailableNotZero() {
        FraudEngineAggregationResult result = resultWithNormalizedEngine(
                AggregationTestSupport.normalized("rules.primary", FraudEngineStatus.AVAILABLE, null, RiskLevel.HIGH, "HIGH_VELOCITY")
        );

        assertThat(mapper.map(result).engines().getFirst().scoreBucket())
                .isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE)
                .isNotEqualTo(EngineIntelligenceScoreBucket.LOW);
    }

    @Test
    void mapsDiagnosticSignalsWithoutRawEvidence() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.HIGH));

        assertThat(json).contains("diagnosticSignals").doesNotContain("evidence", "description", "contribution");
    }

    @Test
    void mapsWarningsAsCodeCountsOnly() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "UNSUPPORTED_SAFE_REASON"),
                AggregationTestSupport.available("ml.python.primary", 0.7d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        )));

        assertThat(summary.warnings()).containsExactly(new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.REASON_CODE_UNSUPPORTED_DROPPED,
                1
        ));
    }

    @Test
    void doesNotExposeInternalAggregationObject() {
        assertThat(Arrays.stream(com.frauddetection.common.events.intelligence.EngineIntelligenceSummary.class
                .getRecordComponents()).map(RecordComponent::getType))
                .doesNotContain(FraudEngineAggregationResult.class);
    }

    private com.frauddetection.common.events.intelligence.EngineIntelligenceSummary map(
            double rulesScore,
            RiskLevel rulesRisk,
            double mlScore,
            RiskLevel mlRisk
    ) {
        return mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", rulesScore, rulesRisk, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", mlScore, mlRisk, "MODEL_HIGH_RISK")
        )));
    }

    private FraudEngineAggregationResult resultWithNormalizedEngine(NormalizedFraudEngineResult normalized) {
        return new FraudEngineAggregationResult(
                List.of(normalized),
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE, null),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        );
    }
}
