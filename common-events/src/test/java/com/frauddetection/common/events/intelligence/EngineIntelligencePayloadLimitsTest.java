package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligencePayloadLimitsTest {

    @Test
    void rejectsTooManyEngineResults() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                Collections.nCopies(3, EngineIntelligenceTestSupport.engine()), List.of(), List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_ENGINES_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsTooManyDiagnosticSignals() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(), Collections.nCopies(6, EngineIntelligenceTestSupport.signal()), List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_DIAGNOSTICSIGNALS_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsTooManyWarnings() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(), List.of(), Collections.nCopies(11, EngineIntelligenceTestSupport.warning())
        )).hasMessage("ENGINE_INTELLIGENCE_WARNINGS_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsTooManyReasonCodes() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.engine(Collections.nCopies(6, "HIGH_VELOCITY")))
                .hasMessage("ENGINE_INTELLIGENCE_REASONCODES_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsTooLongReasonCode() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.engine(List.of("A".repeat(129))))
                .hasMessage("ENGINE_INTELLIGENCE_REASON_CODE_INVALID");
    }

    @Test
    void rejectsTooLongEngineId() {
        assertThatThrownBy(() -> new EngineIntelligenceEngineResult(
                "r".repeat(129),
                com.frauddetection.common.events.engine.FraudEngineType.RULES,
                com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE,
                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_ENGINE_ID_INVALID");
    }

    @Test
    void worstCasePayloadSerializesWithinBoundedSize() throws Exception {
        EngineIntelligenceSummary summary = EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.engine(Collections.nCopies(5, "HIGH_VELOCITY")),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                com.frauddetection.common.events.engine.FraudEngineType.ML_MODEL,
                                com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE,
                                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                Collections.nCopies(5, "MODEL_HIGH_RISK")
                        )
                ),
                Collections.nCopies(5, EngineIntelligenceTestSupport.signal()),
                Collections.nCopies(10, EngineIntelligenceTestSupport.warning())
        );

        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsBytes(summary)).hasSizeLessThan(8192);
    }

    @Test
    void payloadDoesNotGrowWithUnboundedEvidenceText() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());

        assertThat(json).doesNotContain("evidence", "description", "raw");
    }
}
