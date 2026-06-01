package com.frauddetection.scoring.config;

import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.contextRunner;
import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.enabledContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceEmissionPropertiesSpringContextTest {

    @Test
    void defaultContextBindsEmitEnabledFalse() {
        contextRunner().run(context ->
                assertThat(context.getBean(EngineIntelligenceEmissionProperties.class).emitEnabled()).isFalse()
        );
    }

    @Test
    void environmentOverrideTrueBindsEmitEnabledTrue() {
        enabledContextRunner()
                .run(context ->
                        assertThat(context.getBean(EngineIntelligenceEmissionProperties.class).emitEnabled()).isTrue()
                );
    }

    @Test
    void environmentOverrideFalseBindsEmitEnabledFalse() {
        contextRunner()
                .withPropertyValues(EngineIntelligenceEmissionProperties.PROPERTY_NAME + "=false")
                .run(context ->
                        assertThat(context.getBean(EngineIntelligenceEmissionProperties.class).emitEnabled()).isFalse()
                );
    }

    @Test
    void propertyBeanIsAvailableForInjection() {
        contextRunner().run(context ->
                assertThat(context).hasSingleBean(EngineIntelligenceEmissionProperties.class)
        );
    }

    @Test
    void emissionServiceBeanReceivesProperties() {
        enabledContextRunner()
                .run(context ->
                        assertThat(context.getBean(EngineIntelligenceEmissionService.class).emitEnabled()).isTrue()
                );
    }
}
