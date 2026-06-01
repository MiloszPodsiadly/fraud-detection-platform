package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceSummaryTest {

    @Test
    void acceptsContractVersionOne() {
        assertThat(EngineIntelligenceTestSupport.summary().contractVersion()).isEqualTo(1);
    }

    @Test
    void rejectsZeroVersion() {
        assertInvalidVersion(0);
    }

    @Test
    void rejectsNegativeVersion() {
        assertInvalidVersion(-1);
    }

    @Test
    void contractVersionIsSerialized() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary()))
                .contains("\"contractVersion\":1");
    }

    @Test
    void contractVersionIsRequiredInPublicShape() {
        assertThat(Arrays.stream(EngineIntelligenceSummary.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("contractVersion");
    }

    @Test
    void missingContractVersionFailsDuringDeserialization() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.objectMapper().readValue("""
                {
                  "generatedAt": "2026-06-01T06:00:00Z",
                  "engines": [],
                  "comparison": {
                    "agreementStatus": "INSUFFICIENT_DATA",
                    "riskMismatchStatus": "NOT_COMPARABLE",
                    "scoreDeltaBucket": "UNAVAILABLE"
                  },
                  "diagnosticSignals": [],
                  "warnings": []
                }
                """, EngineIntelligenceSummary.class))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void defensivelyCopiesListsAndRejectsNullEntries() {
        List<EngineIntelligenceEngineResult> engines = new ArrayList<>(List.of(EngineIntelligenceTestSupport.engine()));
        EngineIntelligenceSummary summary = EngineIntelligenceTestSupport.summary(engines, List.of(), List.of());

        engines.clear();

        assertThat(summary.engines()).hasSize(1);
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                Arrays.asList((EngineIntelligenceEngineResult) null),
                List.of(),
                List.of()
        )).isInstanceOf(NullPointerException.class);
    }

    private void assertInvalidVersion(int version) {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(
                version,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(),
                EngineIntelligenceTestSupport.comparison(),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }
}
