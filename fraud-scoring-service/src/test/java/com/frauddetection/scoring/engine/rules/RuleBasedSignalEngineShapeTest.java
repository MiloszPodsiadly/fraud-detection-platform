package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineShapeTest {

    @Test
    void implementsFraudSignalEngineWithExpectedMethods() throws Exception {
        assertThat(FraudSignalEngine.class).isAssignableFrom(RuleBasedSignalEngine.class);
        assertThat(RuleBasedSignalEngine.class.getMethod("evaluate", ScoringContext.class).getReturnType())
                .isEqualTo(FraudEngineResult.class);
        assertThat(RuleBasedSignalEngine.class.getMethod("descriptor").getReturnType())
                .isEqualTo(FraudEngineDescriptor.class);
    }

    @Test
    void isFinalDependencyBackedAndNotSpringManaged() {
        assertThat(Modifier.isFinal(RuleBasedSignalEngine.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(RuleBasedSignalEngine.class.getConstructors())
                .noneMatch(constructor -> constructor.getParameterCount() == 0))
                .isTrue();
        assertThat(RuleBasedSignalEngine.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(FeatureSnapshotReaderFactory.class, RuleBasedFraudScoringEngine.class));
        assertThat(Arrays.stream(RuleBasedSignalEngine.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .doesNotContain("Component", "Service", "Repository", "Controller", "Configuration");
    }

    @Test
    void exposesNoOrchestratorDecisionOrFallbackMethods() {
        assertThat(Arrays.stream(RuleBasedSignalEngine.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("orchestrate", "runAll", "aggregate", "route", "fallback", "decide", "approve", "decline");
    }
}
