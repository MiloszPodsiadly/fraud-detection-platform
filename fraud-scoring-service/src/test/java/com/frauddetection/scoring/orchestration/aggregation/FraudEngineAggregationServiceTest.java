package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineAggregationServiceTest {
    private final FraudEngineAggregationService service =
            new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy());

    @Test
    void aggregationIsDeterministicRegardlessOfInputOrder() {
        FraudScoringOrchestrationResult ordered = AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK")
        );
        FraudScoringOrchestrationResult reversed = AggregationTestSupport.orchestration(
                AggregationTestSupport.available("ml.python.primary", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK"),
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY")
        );

        assertThat(service.aggregate(reversed)).isEqualTo(service.aggregate(ordered));
        assertThat(service.aggregate(ordered).normalizedEngineResults())
                .extracting(NormalizedFraudEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void velocityIsNormalizedAsThirdEngineButComparisonStaysRulesVersusMl() {
        FraudScoringOrchestrationResult orchestration = AggregationTestSupport.orchestration(
                AggregationTestSupport.available("velocity.primary", 0.95d, RiskLevel.CRITICAL, "RAPID_PLN_20K_BURST"),
                AggregationTestSupport.available("ml.python.primary", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK"),
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY")
        );

        FraudEngineAggregationResult result = service.aggregate(orchestration);

        assertThat(result.normalizedEngineResults())
                .extracting(NormalizedFraudEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
        assertThat(result.scoreDelta().status()).isEqualTo(FraudEngineScoreDeltaStatus.AVAILABLE);
        assertThat(result.scoreDelta().absoluteDelta()).isBetween(0.699d, 0.701d);
        assertThat(result.riskMismatch().status()).isEqualTo(FraudEngineRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
        assertThat(result.agreementStatus()).isEqualTo(FraudEngineAgreementStatus.DISAGREEMENT);
    }

    @Test
    void officialVelocityContributionsDoNotCreateRoutineSanitizerWarnings() {
        FraudScoringOrchestrationResult orchestration = AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK"),
                AggregationTestSupport.raw(
                        "velocity.primary",
                        FraudEngineStatus.AVAILABLE,
                        0.95d,
                        RiskLevel.CRITICAL,
                        List.of("RAPID_PLN_20K_BURST"),
                        List.of(new FraudEngineContribution(
                                "RAPID_TRANSFER_PLN_BURST",
                                null,
                                0.4d,
                                FraudEngineContributionDirection.INCREASES_RISK
                        )),
                        List.of()
                )
        );

        FraudEngineAggregationResult result = service.aggregate(orchestration);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.normalizedEngineResults().get(2).contributions()).hasSize(1);
    }

    @Test
    void operationalEngineStatusesRemainVisible() {
        for (FraudEngineStatus status : List.of(FraudEngineStatus.TIMEOUT, FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.DEGRADED)) {
            FraudEngineAggregationResult result = service.aggregate(AggregationTestSupport.orchestration(
                    AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                    AggregationTestSupport.unavailable("ml.python.primary", status, reasonFor(status))
            ));

            assertThat(result.normalizedEngineResults().get(1).status()).isEqualTo(status);
            assertThat(result.agreementStatus()).isEqualTo(FraudEngineAgreementStatus.PARTIAL);
        }
    }

    @Test
    void resultAndWarningsAreBounded() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(1, 1, 1, 1, 1, 1, 128, 120, 256);
        FraudEngineAggregationService strictService = new FraudEngineAggregationService(strict);
        FraudEngineAggregationResult result = strictService.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY", "HIGH_TRANSACTION_AMOUNT"),
                AggregationTestSupport.available("ml.python.primary", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK")
        ));

        assertThat(result.normalizedEngineResults()).hasSize(1);
        assertThat(result.strongestSignals()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void rejectsDuplicateEngineIdentityBeforeComputingAgreement() {
        assertThatThrownBy(() -> service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.available("rules.primary", 0.2d, RiskLevel.LOW, "LOW_TRANSACTION_AMOUNT")
        ))).hasMessage("AGGREGATION_DUPLICATE_ENGINE_ID");
    }

    @Test
    void rejectsRulesPrimaryWithMlModelTypeBeforeAggregation() {
        assertThatThrownBy(() -> service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.raw(
                        "rules.primary",
                        FraudEngineType.ML_MODEL,
                        FraudEngineStatus.AVAILABLE,
                        0.9d,
                        RiskLevel.HIGH,
                        List.of("HIGH_VELOCITY"),
                        List.of(),
                        List.of()
                )
        ))).hasMessage("AGGREGATION_ENGINE_TYPE_MISMATCH")
                .message()
                .doesNotContain("rules.primary", "ML_MODEL");
    }

    @Test
    void rejectsMlPythonPrimaryWithRulesTypeBeforeAggregation() {
        assertThatThrownBy(() -> service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.raw(
                        "ml.python.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        0.9d,
                        RiskLevel.HIGH,
                        List.of("MODEL_HIGH_RISK"),
                        List.of(),
                        List.of()
                )
        ))).hasMessage("AGGREGATION_ENGINE_TYPE_MISMATCH")
                .message()
                .doesNotContain("ml.python.primary", "RULES");
    }

    @Test
    void rejectsUnknownEngineIdentityBeforeComputingAgreement() {
        assertThatThrownBy(() -> service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.9d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.available("merchant.experimental", 0.2d, RiskLevel.LOW, "LOW_MODEL_RISK")
        ))).hasMessage("AGGREGATION_UNKNOWN_ENGINE_ID")
                .message()
                .doesNotContain("merchant.experimental");
    }

    private String reasonFor(FraudEngineStatus status) {
        return status == FraudEngineStatus.TIMEOUT ? "ORCHESTRATOR_ENGINE_TIMEOUT" : "ML_MODEL_UNAVAILABLE";
    }
}
