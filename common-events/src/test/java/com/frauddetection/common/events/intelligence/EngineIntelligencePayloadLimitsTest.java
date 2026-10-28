package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligencePayloadLimitsTest {

    @Test
    void rejectsFiveEngineResultsBeforeIdentityValidation() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                Collections.nCopies(5, EngineIntelligenceTestSupport.engine()), List.of(), List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_ENGINES_LIMIT_EXCEEDED");
    }

    @Test
    void acceptsTwoRequiredEnginesWithOptionalVelocityAbsentOrPresent() {
        assertThat(EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.engine(),
                        EngineIntelligenceTestSupport.mlEngine(
                                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH
                        ),
                        new EngineIntelligenceEngineResult(
                                "velocity.primary",
                                com.frauddetection.common.events.engine.FraudEngineType.VELOCITY,
                                com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE,
                                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("RAPID_PLN_20K_BURST")
                        )
                ),
                List.of(),
                List.of()
        ).engines()).hasSize(3);

        assertThat(EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.engine(),
                        EngineIntelligenceTestSupport.mlEngine(
                                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH
                        )
                ),
                List.of(),
                List.of()
        ).engines()).hasSize(2);
    }

    @Test
    void acceptsExactlyFourValidEngineResultsInCanonicalOrder() {
        EngineIntelligenceSummary summary = EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        new EngineIntelligenceEngineResult(
                                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                                FraudEngineType.VELOCITY,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("RAPID_PLN_20K_BURST")
                        ),
                        new EngineIntelligenceEngineResult(
                                FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID,
                                FraudEngineType.DEVICE_RISK,
                                FraudEngineStatus.UNAVAILABLE,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("DEVICE_EVIDENCE_NOT_ADMITTED")
                        )
                ),
                List.of(),
                List.of()
        );

        assertThat(summary.engines())
                .extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
    }

    @Test
    void rejectsDuplicateEngineIdentityIndependentlyOfEngineCountLimit() {
        EngineIntelligenceEngineResult velocity = new EngineIntelligenceEngineResult(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("RAPID_PLN_20K_BURST")
        );

        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        velocity,
                        velocity
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_DUPLICATE_ENGINE_ID");
    }

    @Test
    void rejectsUnknownEngineIdentityIndependentlyOfEngineCountLimit() {
        assertThatThrownBy(() -> new EngineIntelligenceEngineResult(
                "future.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_RISK_FLAGS_PRESENT")
        )).hasMessage("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
    }

    @Test
    void rejectsProfilesMissingEitherRequiredRulesOrMlEngine() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        new EngineIntelligenceEngineResult(
                                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                                FraudEngineType.VELOCITY,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("RAPID_PLN_20K_BURST")
                        )
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.summary(
                List.of(
                        EngineIntelligenceTestSupport.rulesEngine(),
                        new EngineIntelligenceEngineResult(
                                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                                FraudEngineType.VELOCITY,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("RAPID_PLN_20K_BURST")
                        )
                ),
                List.of(),
                List.of()
        )).hasMessage("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
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
                                Collections.nCopies(5, "MODEL_HIGH_RISK"),
                                EngineIntelligenceTestSupport.mlIdentity()
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
