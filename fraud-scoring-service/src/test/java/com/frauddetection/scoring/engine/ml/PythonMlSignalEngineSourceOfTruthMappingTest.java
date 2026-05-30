package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.context;
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
}
