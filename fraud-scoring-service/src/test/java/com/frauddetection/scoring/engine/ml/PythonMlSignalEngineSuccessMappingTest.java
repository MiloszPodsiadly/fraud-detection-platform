package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.context;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.flatten;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.result;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.validResult;
import static org.assertj.core.api.Assertions.assertThat;

class PythonMlSignalEngineSuccessMappingTest {

    @Test
    void validMlResultMapsToAvailableFraudEngineResult() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(sourceReturning(validResult(0.82d, RiskLevel.HIGH)));

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.82d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.confidence()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineConfidence.MEDIUM);
        assertThat(result.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(result.modelVersion()).isEqualTo("2026-05-30.v1");
        assertThat(result.generatedAt()).isEqualTo(PythonMlSignalEngineTestSupport.RECEIVED_AT);
        assertThat(result.latencyMs()).isZero();
        assertThat(result.reasonCodes()).containsExactly(PythonMlSignalReasonCode.ML_MODEL_SIGNAL.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(PythonMlSignalReasonCode.ML_MODEL_SIGNAL.wireValue());
    }

    @Test
    void successMapsSourceReasonCodes() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceReturning(result(
                        0.82d,
                        RiskLevel.HIGH,
                        "python-logistic-fraud-model",
                        "2026-05-30.v1",
                        true,
                        java.util.List.of(ReasonCode.MODEL_HIGH_RISK.wireValue())
                ))
        );

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(result.contributions()).extracting(contribution -> contribution.feature())
                .containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
    }

    @Test
    void successFallsBackToMlModelSignalWhenSourceReasonsEmpty() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(sourceReturning(validResult(0.82d, RiskLevel.HIGH)));

        var result = adapter.evaluate(context());

        assertThat(result.reasonCodes()).containsExactly(PythonMlSignalReasonCode.ML_MODEL_SIGNAL.wireValue());
    }

    @Test
    void unsupportedSourceReasonsAreNotLeaked() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceReturning(result(
                        0.82d,
                        RiskLevel.HIGH,
                        "python-logistic-fraud-model",
                        "2026-05-30.v1",
                        true,
                        java.util.List.of("rawFeatureVector=VIP", "http://ml-internal", "token", "stacktrace")
                ))
        );

        var result = adapter.evaluate(context());

        assertThat(result.reasonCodes()).containsExactly(PythonMlSignalReasonCode.ML_MODEL_SIGNAL.wireValue());
        assertThat(flatten(result))
                .doesNotContain("rawFeatureVector=VIP")
                .doesNotContain("http://ml-internal")
                .doesNotContain("token")
                .doesNotContain("stacktrace");
    }

    @Test
    void mixedSupportedAndUnsupportedReasonsKeepOnlySupported() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceReturning(result(
                        0.82d,
                        RiskLevel.HIGH,
                        "python-logistic-fraud-model",
                        "2026-05-30.v1",
                        true,
                        java.util.List.of(ReasonCode.MODEL_HIGH_RISK.wireValue(), "rawFeatureVector=VIP")
                ))
        );

        var result = adapter.evaluate(context());

        assertThat(result.reasonCodes()).containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(flatten(result))
                .doesNotContain(PythonMlSignalReasonCode.ML_MODEL_SIGNAL.wireValue())
                .doesNotContain("rawFeatureVector=VIP");
    }

    @Test
    void successResultDoesNotExposeRawMlData() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceReturning(PythonMlSignalEngineTestSupport.resultWithUnsafeDiagnostics())
        );

        var result = adapter.evaluate(context());

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(flatten(result))
                .doesNotContain("rawFeatureVector")
                .doesNotContain("rawModelPayload")
                .doesNotContain("request body")
                .doesNotContain("response body")
                .doesNotContain("VIP")
                .doesNotContain("acct-secret")
                .doesNotContain("tx-secret")
                .doesNotContain("http://ml-internal")
                .doesNotContain("token")
                .doesNotContain("stacktrace")
                .doesNotContain("PythonException");
    }
}
