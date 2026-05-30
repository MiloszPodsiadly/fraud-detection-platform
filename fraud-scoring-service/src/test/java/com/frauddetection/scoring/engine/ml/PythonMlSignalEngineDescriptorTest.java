package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.validResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PythonMlSignalEngineDescriptorTest {

    @Test
    void descriptorUsesStaticPythonMlIdentity() {
        FraudEngineDescriptor descriptor = newAdapter().descriptor();

        assertThat(descriptor.engineId()).isEqualTo("ml.python.primary");
        assertThat(descriptor.engineType()).isEqualTo(FraudEngineType.ML_MODEL);
        assertThat(descriptor.engineLanguage()).isEqualTo("python");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.required()).isFalse();
        assertThatCode(() -> new FraudEngineDescriptor(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                descriptor.version(),
                descriptor.required()
        )).doesNotThrowAnyException();
    }

    @Test
    void requiredFalseRemainsDescriptorMetadataOnly() {
        FraudEngineDescriptor descriptor = newAdapter().descriptor();

        assertThat(descriptor.required()).isFalse();
        assertThat(Arrays.stream(PythonMlSignalEngine.class.getDeclaredMethods()).map(method -> method.getName()))
                .doesNotContain("fallback", "ignoreFailure", "defaultLowRisk", "orchestrate", "decide", "approve", "decline");
    }

    @Test
    void reasonCodeEnumContainsOnlyFdp88EmittedAdapterCodes() {
        assertThat(Arrays.stream(PythonMlSignalReasonCode.values()).map(PythonMlSignalReasonCode::wireValue))
                .containsExactly(
                        "ML_MODEL_SIGNAL",
                        "ML_MODEL_UNAVAILABLE",
                        "ML_MODEL_TIMEOUT",
                        "ML_MODEL_INVALID_RESPONSE",
                        "ML_SCORE_MISSING",
                        "ML_SCORE_OUT_OF_RANGE",
                        "ML_MODEL_METADATA_MISSING",
                        "ML_AVAILABILITY_METADATA_MISSING",
                        "ML_AVAILABILITY_METADATA_INVALID",
                        "ML_CLIENT_ERROR"
                );
    }

    private PythonMlSignalEngine newAdapter() {
        return new PythonMlSignalEngine(sourceReturning(validResult(0.82d, RiskLevel.HIGH)));
    }
}
