package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProducerEngineIntelligenceDisabledByDefaultTest {

    @Test
    void defaultConfigDoesNotEmitEngineIntelligence() {
        assertDisabled(new EngineIntelligenceEmissionProperties(false));
    }

    @Test
    void explicitFalseDoesNotEmitEngineIntelligence() {
        assertDisabled(new EngineIntelligenceEmissionProperties(false));
    }

    @Test
    void disabledFlagDoesNotInvokePublicMapperOrAggregationSupplier() {
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        AtomicBoolean invoked = new AtomicBoolean();
        var service = new EngineIntelligenceEmissionService(new EngineIntelligenceEmissionProperties(false), mapper);

        assertThat(service.mapIfEnabled(() -> {
            invoked.set(true);
            throw new IllegalStateException("must not be called");
        })).isEmpty();
        assertThat(invoked).isFalse();
        verifyNoInteractions(mapper);
    }

    private void assertDisabled(EngineIntelligenceEmissionProperties properties) {
        assertThat(new EngineIntelligenceEmissionService(properties).mapIfEnabled(() -> {
            throw new IllegalStateException("must not be called");
        })).isEmpty();
    }
}
