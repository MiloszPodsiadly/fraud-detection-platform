package com.frauddetection.scoring.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceEmissionPropertiesTest {

    @Test
    void missingPropertyMeansDisabled() {
        assertThat(bind(Map.of()).emitEnabled()).isFalse();
    }

    @Test
    void explicitFalseMeansDisabled() {
        assertThat(bind(Map.of(EngineIntelligenceEmissionProperties.PROPERTY_NAME, "false")).emitEnabled()).isFalse();
    }

    @Test
    void explicitTrueMeansEnabled() {
        assertThat(bind(Map.of(EngineIntelligenceEmissionProperties.PROPERTY_NAME, "true")).emitEnabled()).isTrue();
    }

    @Test
    void productionLikeDefaultIsDisabled() throws Exception {
        assertThat(Files.readString(moduleRoot().resolve("src/main/resources/application.yml")))
                .contains("emit-enabled: ${FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED:false}");
    }

    private EngineIntelligenceEmissionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind(
                        EngineIntelligenceEmissionProperties.PREFIX,
                        Bindable.of(EngineIntelligenceEmissionProperties.class)
                )
                .orElseGet(() -> new EngineIntelligenceEmissionProperties(false));
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main/resources/application.yml"))
                ? current
                : current.resolve("fraud-scoring-service");
    }
}
