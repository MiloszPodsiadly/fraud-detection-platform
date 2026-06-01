package com.frauddetection.scoring.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceEmissionFlagNamingTest {

    @Test
    void flagNameIsSpecificToScoringEventEmission() {
        assertThat(EngineIntelligenceEmissionProperties.PROPERTY_NAME)
                .isEqualTo("fraud.scoring.events.engine-intelligence.emit-enabled")
                .contains("scoring", "events", "engine-intelligence", "emit");
    }

    @Test
    void recordDefaultValueIsDisabled() {
        assertThat(new EngineIntelligenceEmissionProperties(false).emitEnabled()).isFalse();
    }
}
