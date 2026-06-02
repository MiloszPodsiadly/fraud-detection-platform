package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineIntelligenceProjectionPolicyTest {

    private final EngineIntelligenceProjectionPolicy policy = new EngineIntelligenceProjectionPolicy();

    @Test
    void acceptsBoundedPublicContractValues() {
        assertThat(policy.validatedCopy(EngineIntelligenceProjectionTestFixtures.fullSummary()))
                .isEqualTo(EngineIntelligenceProjectionTestFixtures.fullSummary());
    }

    @Test
    void rejectsTooManyEngines() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.engines()).thenReturn(Collections.nCopies(3, EngineIntelligenceProjectionTestFixtures.timeoutMl()));

        assertLimitExceeded(source, "ENGINES");
    }

    @Test
    void rejectsTooManyDiagnosticSignals() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.diagnosticSignals()).thenReturn(Collections.nCopies(
                6,
                EngineIntelligenceProjectionTestFixtures.operationalMlSignal()
        ));

        assertLimitExceeded(source, "DIAGNOSTIC_SIGNALS");
    }

    @Test
    void rejectsTooManyWarnings() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.warnings()).thenReturn(Collections.nCopies(11, mock(EngineIntelligenceWarningSummary.class)));

        assertLimitExceeded(source, "WARNINGS");
    }

    @Test
    void rejectsTooManyReasonCodes() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("A", "B", "C", "D", "E", "F"));
        when(source.engines()).thenReturn(List.of(engine));

        assertLimitExceeded(source, "REASON_CODES");
    }

    @Test
    void rejectsOverlongStrings() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("x".repeat(129), List.of("HIGH_VELOCITY"));
        when(source.engines()).thenReturn(List.of(engine));

        assertThatThrownBy(() -> policy.validatedCopy(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_ENGINE_ID_INVALID");
    }

    @Test
    void dropsOrRejectsUnsupportedReasonCodeBoundedly() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("NOT_ALLOWLISTED"));
        when(source.engines()).thenReturn(List.of(engine));

        assertThatThrownBy(() -> policy.validatedCopy(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED");
    }

    @Test
    void rejectsForbiddenRawValueText() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("raw_payload"));
        when(source.engines()).thenReturn(List.of(engine));

        assertThatThrownBy(() -> policy.validatedCopy(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_REASON_CODE_INVALID");
    }

    @Test
    void acceptsOperationalStatusWithoutRiskLevel() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.engines()).thenReturn(List.of(EngineIntelligenceProjectionTestFixtures.timeoutMl()));

        assertThat(policy.validatedCopy(source).engines().getFirst().riskLevel()).isNull();
    }

    @Test
    void acceptsOperationalSignalWithoutRiskLevel() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.diagnosticSignals()).thenReturn(List.of(EngineIntelligenceProjectionTestFixtures.operationalMlSignal()));

        assertThat(policy.validatedCopy(source).diagnosticSignals().getFirst().riskLevel()).isNull();
    }

    private EngineIntelligenceSummary summaryMock() {
        EngineIntelligenceSummary source = mock(EngineIntelligenceSummary.class);
        when(source.contractVersion()).thenReturn(EngineIntelligenceSummary.CONTRACT_VERSION);
        when(source.generatedAt()).thenReturn(EngineIntelligenceProjectionTestFixtures.GENERATED_AT);
        when(source.engines()).thenReturn(List.of());
        when(source.comparison()).thenReturn(EngineIntelligenceProjectionTestFixtures.comparison(
                com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        ));
        when(source.diagnosticSignals()).thenReturn(List.of());
        when(source.warnings()).thenReturn(List.of());
        return source;
    }

    private EngineIntelligenceEngineResult engineMock(String engineId, List<String> reasonCodes) {
        EngineIntelligenceEngineResult engine = mock(EngineIntelligenceEngineResult.class);
        when(engine.engineId()).thenReturn(engineId);
        when(engine.engineType()).thenReturn(FraudEngineType.RULES);
        when(engine.status()).thenReturn(FraudEngineStatus.AVAILABLE);
        when(engine.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(engine.scoreBucket()).thenReturn(EngineIntelligenceScoreBucket.HIGH);
        when(engine.reasonCodes()).thenReturn(reasonCodes);
        return engine;
    }

    private void assertLimitExceeded(EngineIntelligenceSummary source, String fieldName) {
        assertThatThrownBy(() -> policy.validatedCopy(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENGINE_INTELLIGENCE_" + fieldName + "_LIMIT_EXCEEDED");
    }
}
