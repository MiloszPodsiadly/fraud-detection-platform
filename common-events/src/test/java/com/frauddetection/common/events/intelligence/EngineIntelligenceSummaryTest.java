package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceSummaryTest {

    @Test
    void acceptsContractVersionOne() {
        assertThat(EngineIntelligenceTestSupport.summary().contractVersion()).isEqualTo(1);
    }

    @Test
    void rejectsZeroVersion() {
        assertInvalidVersion(0);
    }

    @Test
    void rejectsNegativeVersion() {
        assertInvalidVersion(-1);
    }

    @Test
    void contractVersionIsSerialized() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary()))
                .contains("\"contractVersion\":1");
    }

    @Test
    void contractVersionIsRequiredInPublicShape() {
        assertThat(Arrays.stream(EngineIntelligenceSummary.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("contractVersion");
    }

    @Test
    void missingContractVersionFailsDuringDeserialization() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.objectMapper().readValue("""
                {
                  "generatedAt": "2026-06-01T06:00:00Z",
                  "engines": [],
                  "comparison": {
                    "comparisonType": "RULES_VS_ML",
                    "comparedEngineIds": ["rules.primary", "ml.python.primary"],
                    "agreementStatus": "INSUFFICIENT_DATA",
                    "riskMismatchStatus": "NOT_COMPARABLE",
                    "scoreDeltaBucket": "UNAVAILABLE"
                  },
                  "diagnosticSignals": [],
                  "warnings": []
                }
                """, EngineIntelligenceSummary.class))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void defensivelyCopiesListsAndRejectsNullEntries() {
        List<EngineIntelligenceEngineResult> engines = new ArrayList<>(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)
        ));
        EngineIntelligenceSummary summary = EngineIntelligenceTestSupport.summary(engines, List.of(), List.of());

        engines.clear();

        assertThat(summary.engines()).hasSize(2);
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                Arrays.asList((EngineIntelligenceEngineResult) null),
                List.of(),
                List.of()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rulesVersusMlComparisonIdentityIsExplicitAndOrdered() {
        EngineIntelligenceComparison comparison = EngineIntelligenceTestSupport.comparison();

        assertThat(comparison.comparisonType()).isEqualTo(EngineIntelligenceComparisonType.RULES_VS_ML);
        assertThat(comparison.comparedEngineIds()).containsExactly("rules.primary", "ml.python.primary");
        assertThatThrownBy(() -> new EngineIntelligenceComparison(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of("rules.primary", "velocity.primary"),
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_ENGINE_IDS_INVALID");
    }

    @Test
    void rejectsSummaryMissingRequiredRulesOrMlEngines() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(List.of(), List.of(), List.of()))
                .hasMessage("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(EngineIntelligenceTestSupport.rulesEngine()),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
    }

    @Test
    void rejectsComparisonContradictingAvailableRulesAndMlRisk() {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.LOW, EngineIntelligenceScoreBucket.LOW)
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_RISK_MISMATCH_INCONSISTENT");
    }

    @Test
    void rejectsOrdinaryAgreementWhenRequiredEngineIsOperationallyUnavailable() {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.operationalMl(FraudEngineStatus.TIMEOUT)
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.SMALL
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
    }

    @Test
    void acceptsSameScoreBucketWithExactMediumDeltaBucket() {
        EngineIntelligenceSummary summary = new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.MEDIUM
                ),
                List.of(),
                List.of()
        );

        assertThat(summary.comparison().scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.MEDIUM);
    }

    @Test
    void rejectsBucketDistanceTwoWithSmallScoreDeltaBucket() {
        assertThatThrownBy(() -> summaryWithScoreBuckets(
                EngineIntelligenceScoreBucket.LOW,
                EngineIntelligenceScoreBucket.HIGH,
                EngineIntelligenceScoreDeltaBucket.SMALL
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
    }

    @Test
    void rejectsLowVeryHighWithMediumScoreDeltaBucket() {
        assertThatThrownBy(() -> summaryWithScoreBuckets(
                EngineIntelligenceScoreBucket.LOW,
                EngineIntelligenceScoreBucket.VERY_HIGH,
                EngineIntelligenceScoreDeltaBucket.MEDIUM
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
    }

    @Test
    void rejectsAvailableRulesAndMlWithUnavailableScoreDeltaBucket() {
        assertThatThrownBy(() -> summaryWithScoreBuckets(
                EngineIntelligenceScoreBucket.LOW,
                EngineIntelligenceScoreBucket.HIGH,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
    }

    @Test
    void rejectsOperationalRequiredEngineWithNumericScoreDeltaBucket() {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.operationalMl(FraudEngineStatus.TIMEOUT)
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.SMALL
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
    }

    @Test
    void rejectsDiagnosticSignalForAbsentOrContradictoryEngine() {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)
                ),
                EngineIntelligenceTestSupport.comparison(),
                List.of(new EngineIntelligenceDiagnosticSignal(
                        "velocity.primary",
                        FraudEngineType.VELOCITY,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        "RAPID_PLN_20K_BURST"
                )),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_DIAGNOSTIC_ENGINE_ABSENT");
    }

    private void assertInvalidVersion(int version) {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                version,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)
                ),
                EngineIntelligenceTestSupport.comparison(),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    private EngineIntelligenceSummary summaryWithScoreBuckets(
            EngineIntelligenceScoreBucket rulesBucket,
            EngineIntelligenceScoreBucket mlBucket,
            EngineIntelligenceScoreDeltaBucket deltaBucket
    ) {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                rulesBucket,
                                List.of("HIGH_VELOCITY")
                        ),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.LOW, mlBucket)
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        deltaBucket
                ),
                List.of(),
                List.of()
        );
    }
}
