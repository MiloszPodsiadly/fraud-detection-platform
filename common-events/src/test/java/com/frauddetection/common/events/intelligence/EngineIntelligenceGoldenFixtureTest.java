package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceGoldenFixtureTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void goldenThreeEngineFixtureDeserializesThroughCommonContract() throws Exception {
        EngineIntelligenceSummary summary = mapper.readValue(
                Files.readString(goldenFixturePath()),
                EngineIntelligenceSummary.class
        );

        assertThat(summary.engines()).hasSize(3);
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineType)
                .containsExactly(FraudEngineType.RULES, FraudEngineType.ML_MODEL, FraudEngineType.VELOCITY);
        assertThat(summary.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid/engine_intelligence_duplicate_velocity.json",
            "invalid/engine_intelligence_too_many_engines.json",
            "invalid/engine_intelligence_velocity_type_mismatch.json"
    })
    void invalidGoldenFixturesAreRejectedByCommonContract(String fixtureName) {
        Path fixture = Path.of("src/test/resources/fixtures/engine-intelligence", fixtureName);

        assertThatThrownBy(() -> mapper.readValue(Files.readString(fixture), EngineIntelligenceSummary.class))
                .isInstanceOf(RuntimeException.class);
    }

    private Path goldenFixturePath() {
        return Path.of("src/test/resources/fixtures/engine-intelligence/engine_intelligence_three_engine_golden.json");
    }
}
