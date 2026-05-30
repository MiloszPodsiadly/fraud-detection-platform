package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RuleBasedSignalEngineDescriptorTest {

    @Test
    void descriptorUsesStaticRulesIdentity() {
        FraudEngineDescriptor descriptor = newAdapter().descriptor();

        assertThat(descriptor.engineId()).isEqualTo("rules.primary");
        assertThat(descriptor.engineType()).isEqualTo(FraudEngineType.RULES);
        assertThat(descriptor.engineLanguage()).isEqualTo("java");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.required()).isTrue();
        assertThatCode(() -> new FraudEngineDescriptor(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                descriptor.version(),
                descriptor.required()
        )).doesNotThrowAnyException();
    }

    @Test
    void requiredFlagRemainsDescriptorMetadataOnly() {
        FraudEngineDescriptor descriptor = newAdapter().descriptor();

        assertThat(descriptor.required()).isTrue();
        assertThat(Arrays.stream(RuleBasedSignalEngine.class.getDeclaredMethods()).map(method -> method.getName()))
                .doesNotContain("fallback", "route", "approve", "decline", "decide");
    }

    @Test
    void adapterHasNoSpringStereotypeAnnotations() {
        assertThat(Arrays.stream(RuleBasedSignalEngine.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .doesNotContain("Component", "Service", "Repository", "Controller", "Configuration");
    }

    private RuleBasedSignalEngine newAdapter() {
        return new RuleBasedSignalEngine(
                new FeatureSnapshotReaderFactory(),
                new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED))
        );
    }
}
