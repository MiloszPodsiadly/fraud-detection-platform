package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
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
