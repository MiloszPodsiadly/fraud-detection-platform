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

    private PythonMlSignalEngine newAdapter() {
        return new PythonMlSignalEngine(sourceReturning(validResult(0.82d, RiskLevel.HIGH)));
    }
}
