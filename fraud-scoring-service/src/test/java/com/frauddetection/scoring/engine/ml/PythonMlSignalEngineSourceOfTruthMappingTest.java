package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.context;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.flatten;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.mlModelOutput;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.realSourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.unavailableResult;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.validResult;
import static org.assertj.core.api.Assertions.assertThat;

class PythonMlSignalEngineSourceOfTruthMappingTest {

    @Test
    void adapterDelegatesToExistingMlSource() {
        PythonMlSignalEngineTestSupport.RecordingMlSource source = sourceReturning(validResult(0.73d, RiskLevel.MEDIUM));
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(source);

        var result = adapter.evaluate(context());

        assertThat(source.calls()).isEqualTo(1);
        assertThat(source.lastRequest()).isNotNull();
        assertThat(result.score()).isEqualTo(0.73d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void adapterDoesNotUseLocalThresholds() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(sourceReturning(validResult(0.99d, RiskLevel.LOW)));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.99d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void adapterDoesNotCreateFallbackDecision() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(sourceReturning(unavailableResult()));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.toString()).doesNotContain("approve", "decline", "finalDecision");
    }

    @Test
    void modelMetadataMapsFromSource() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(sourceReturning(validResult(0.62d, RiskLevel.MEDIUM)));

        var result = adapter.evaluate(context());

        assertThat(result.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(result.modelVersion()).isEqualTo("2026-05-30.v1");
    }

    @Test
    void realMlOutputAvailableMapsScoreRiskModelAndReasons() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(realSourceReturning(mlModelOutput(
                true,
                0.91d,
                RiskLevel.HIGH,
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                java.util.List.of(ReasonCode.MODEL_HIGH_RISK.wireValue())
        )));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.91d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(result.modelVersion()).isEqualTo("2026-05-30.v1");
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
    }

    @Test
    void realMlOutputUnavailableMapsUnavailable() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(realSourceReturning(mlModelOutput(
                false,
                0.0d,
                RiskLevel.LOW,
                "python-logistic-fraud-model",
                "unavailable",
                java.util.List.of(ReasonCode.ML_MODEL_UNAVAILABLE.wireValue())
        )));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo(PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE.wireValue());
        assertThat(flatten(result)).doesNotContain("LOW");
    }

    @Test
    void realMlOutputUnsupportedReasonsDoNotLeak() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(realSourceReturning(mlModelOutput(
                true,
                0.91d,
                RiskLevel.HIGH,
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                java.util.List.of(ReasonCode.MODEL_HIGH_RISK.wireValue(), "rawFeatureVector=VIP")
        )));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(flatten(result)).doesNotContain("rawFeatureVector=VIP");
    }

    @Test
    void realMlOutputInvalidScoreMapsDegraded() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(realSourceReturning(mlModelOutput(
                true,
                1.01d,
                RiskLevel.HIGH,
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                java.util.List.of(ReasonCode.MODEL_HIGH_RISK.wireValue())
        )));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo(PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE.wireValue());
    }
}
