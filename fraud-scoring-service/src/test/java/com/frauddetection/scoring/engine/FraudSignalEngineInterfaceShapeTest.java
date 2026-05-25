package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.scoring.context.ScoringContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FraudSignalEngineInterfaceShapeTest {

    @Test
    void isAnInterfaceWithExactlyEvaluateAndDescriptorMethods() {
        assertThat(FraudSignalEngine.class.isInterface()).isTrue();
        assertThat(Arrays.stream(FraudSignalEngine.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("evaluate", "descriptor")
                .doesNotContain(
                        "isAvailable", "priority", "timeout", "weight", "health", "metadata",
                        "configuration", "endpoint", "supports", "fallback"
                );
    }

    @Test
    void evaluateUsesScoringContextAndReturnsEngineResult() throws Exception {
        Method evaluate = FraudSignalEngine.class.getDeclaredMethod("evaluate", ScoringContext.class);

        assertThat(evaluate.getParameterTypes()).containsExactly(ScoringContext.class);
        assertThat(evaluate.getReturnType()).isEqualTo(FraudEngineResult.class);
    }

    @Test
    void descriptorExposesOnlyStaticDescriptor() throws Exception {
        Method descriptor = FraudSignalEngine.class.getDeclaredMethod("descriptor");

        assertThat(descriptor.getParameterTypes()).isEmpty();
        assertThat(descriptor.getReturnType()).isEqualTo(FraudEngineDescriptor.class);
    }

    @Test
    void carriesNoRuntimeComponentStereotype() {
        assertThat(Arrays.stream(FraudSignalEngine.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .doesNotContain("Component", "Service", "Repository", "Controller");
    }
}
