package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PythonMlSignalEngineShapeTest {

    @Test
    void implementsFraudSignalEngineWithExpectedMethods() throws Exception {
        assertThat(FraudSignalEngine.class).isAssignableFrom(PythonMlSignalEngine.class);
        assertThat(PythonMlSignalEngine.class.getMethod("evaluate", ScoringContext.class).getReturnType())
                .isEqualTo(FraudEngineResult.class);
        assertThat(PythonMlSignalEngine.class.getMethod("descriptor").getReturnType())
                .isEqualTo(FraudEngineDescriptor.class);
    }

    @Test
    void isFinalDependencyBackedAndNotSpringManaged() {
        assertThat(Modifier.isFinal(PythonMlSignalEngine.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(PythonMlSignalEngine.class.getConstructors())
                .noneMatch(constructor -> constructor.getParameterCount() == 0))
                .isTrue();
        assertThat(PythonMlSignalEngine.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(MlFraudScoringEngine.class));
        assertThat(Arrays.stream(PythonMlSignalEngine.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .doesNotContain("Component", "Service", "Repository", "Controller", "Configuration", "Bean");
    }

    @Test
    void exposesNoOrchestratorDecisionOrFallbackMethods() {
        assertThat(Arrays.stream(PythonMlSignalEngine.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("orchestrate", "runAll", "aggregate", "route", "fallback", "decide", "approve", "decline");
    }
}
