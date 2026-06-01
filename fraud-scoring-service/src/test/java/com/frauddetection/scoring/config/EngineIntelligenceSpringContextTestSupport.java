package com.frauddetection.scoring.config;

import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.mockito.Mockito.mock;

final class EngineIntelligenceSpringContextTestSupport {

    private EngineIntelligenceSpringContextTestSupport() {
    }

    static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(EngineIntelligenceRuntimeConfig.class)
                .withBean(ScoringProperties.class, () -> new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    }

    static ApplicationContextRunner enabledContextRunner() {
        return contextRunner()
                .withPropertyValues(EngineIntelligenceEmissionProperties.PROPERTY_NAME + "=true")
                .withBean(RuleBasedFraudScoringEngine.class, () -> mock(RuleBasedFraudScoringEngine.class))
                .withBean(MlFraudScoringEngine.class, () -> mock(MlFraudScoringEngine.class));
    }
}
