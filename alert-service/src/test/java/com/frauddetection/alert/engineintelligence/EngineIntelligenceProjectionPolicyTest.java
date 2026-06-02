package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
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
    void alertServiceStorageLimitsStillApplyAfterPublicContractValidation() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.engines()).thenReturn(Collections.nCopies(3, EngineIntelligenceProjectionTestFixtures.timeoutMl()));

        assertOversized(source);
    }

    @Test
    void rejectsTooManyDiagnosticSignals() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.diagnosticSignals()).thenReturn(Collections.nCopies(
                6,
                EngineIntelligenceProjectionTestFixtures.operationalMlSignal()
        ));

        assertOversized(source);
    }

    @Test
    void rejectsTooManyWarnings() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.warnings()).thenReturn(Collections.nCopies(11, mock(EngineIntelligenceWarningSummary.class)));

        assertOversized(source);
    }

    @Test
    void rejectsTooManyReasonCodes() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("A", "B", "C", "D", "E", "F"));
        when(source.engines()).thenReturn(List.of(engine));

        assertOversized(source);
    }

    @Test
    void rejectsOverlongEngineId() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("x".repeat(129), List.of("HIGH_VELOCITY"));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
    }

    @Test
    void rejectsOverlongReasonCode() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("x".repeat(129)));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
    }

    @Test
    void rejectsOverlongWarningCode() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceWarningSummary warning = warningMock("x".repeat(129));
        when(source.warnings()).thenReturn(List.of(warning));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
    }

    @Test
    void supportedPublicReasonCodeProjects() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("HIGH_VELOCITY"));
        when(source.engines()).thenReturn(List.of(engine));

        assertThat(policy.validatedCopy(source).engines().getFirst().reasonCodes())
                .containsExactly("HIGH_VELOCITY");
    }

    @Test
    void unsupportedReasonCodeRejectedByPublicContract() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("NOT_ALLOWLISTED"));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED);
    }

    @Test
    void rejectsForbiddenRawEngineId() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rawEvidence.primary", List.of("HIGH_VELOCITY"));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void rawTokenRejectedByAlertServiceStoragePolicy() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("raw_payload"));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void rejectsForbiddenRawWarningCode() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceWarningSummary warning = warningMock("rawContribution");
        when(source.warnings()).thenReturn(List.of(warning));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void rejectsForbiddenEndpointTokenSecretStacktraceText() {
        for (String value : List.of("endpoint", "token", "secret", "stack trace", "exception", "debug")) {
            EngineIntelligenceSummary source = summaryMock();
            EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of(value));
            when(source.engines()).thenReturn(List.of(engine));

            assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
    }

    @Test
    void unknownEngineIdRejectedWithEngineIdInvalid() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.secondary", List.of("HIGH_VELOCITY"));
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void warningCodeAcceptedWhenPublic() {
        EngineIntelligenceSummary source = summaryMock();
        when(source.warnings()).thenReturn(List.of(new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED,
                1
        )));

        assertThat(policy.validatedCopy(source).warnings())
                .containsExactly(new EngineIntelligenceWarningSummary(EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED, 1));
    }

    @Test
    void unknownWarningCodeRejected() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceWarningSummary warning = warningMock("UNKNOWN_WARNING");
        when(source.warnings()).thenReturn(List.of(warning));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void unknownEngineStatusRejected() {
        FraudEngineStatus status = mock(FraudEngineStatus.class);
        when(status.toString()).thenReturn("UNKNOWN_STATUS");
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = engineMock("rules.primary", List.of("HIGH_VELOCITY"));
        when(engine.status()).thenReturn(status);
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void diagnosticSignalUnsupportedReasonCodeRejected() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceDiagnosticSignal signal = diagnosticSignalMock("NOT_ALLOWLISTED");
        when(source.diagnosticSignals()).thenReturn(List.of(signal));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED);
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

    @Test
    void operationalStatusWithRiskLevelRejectedByPublicContract() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = operationalEngineMock(FraudEngineStatus.TIMEOUT);
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void unavailableEngineDoesNotStoreRiskLevel() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceEngineResult engine = operationalEngineMock(FraudEngineStatus.UNAVAILABLE);
        when(source.engines()).thenReturn(List.of(engine));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void operationalSignalDoesNotStoreRiskLevel() {
        EngineIntelligenceSummary source = summaryMock();
        EngineIntelligenceDiagnosticSignal signal = diagnosticSignalMock("ML_MODEL_TIMEOUT");
        when(signal.engineId()).thenReturn("ml.python.primary");
        when(signal.engineType()).thenReturn(FraudEngineType.ML_MODEL);
        when(signal.engineStatus()).thenReturn(FraudEngineStatus.TIMEOUT);
        when(signal.signalCategory()).thenReturn(EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL);
        when(signal.riskLevel()).thenReturn(RiskLevel.LOW);
        when(signal.scoreBucket()).thenReturn(EngineIntelligenceScoreBucket.UNAVAILABLE);
        when(source.diagnosticSignals()).thenReturn(List.of(signal));

        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
    }

    @Test
    void typedValidationExceptionMessageContainsOnlyBoundedReason() {
        assertThatThrownBy(() -> policy.validatedTransactionId("txn-secret-rawPayload"))
                .isInstanceOf(EngineIntelligenceProjectionValidationException.class)
                .hasMessage(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE.name())
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("rawPayload");
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

    private EngineIntelligenceDiagnosticSignal diagnosticSignalMock(String reasonCode) {
        EngineIntelligenceDiagnosticSignal signal = mock(EngineIntelligenceDiagnosticSignal.class);
        when(signal.engineId()).thenReturn("rules.primary");
        when(signal.engineType()).thenReturn(FraudEngineType.RULES);
        when(signal.engineStatus()).thenReturn(FraudEngineStatus.AVAILABLE);
        when(signal.signalCategory()).thenReturn(EngineIntelligenceSignalCategory.FRAUD_SIGNAL);
        when(signal.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(signal.scoreBucket()).thenReturn(EngineIntelligenceScoreBucket.HIGH);
        when(signal.reasonCode()).thenReturn(reasonCode);
        return signal;
    }

    private EngineIntelligenceEngineResult operationalEngineMock(FraudEngineStatus status) {
        EngineIntelligenceEngineResult engine = mock(EngineIntelligenceEngineResult.class);
        when(engine.engineId()).thenReturn("ml.python.primary");
        when(engine.engineType()).thenReturn(FraudEngineType.ML_MODEL);
        when(engine.status()).thenReturn(status);
        when(engine.riskLevel()).thenReturn(RiskLevel.LOW);
        when(engine.scoreBucket()).thenReturn(EngineIntelligenceScoreBucket.UNAVAILABLE);
        when(engine.reasonCodes()).thenReturn(List.of("ML_MODEL_TIMEOUT"));
        return engine;
    }

    private EngineIntelligenceWarningSummary warningMock(String codeName) {
        EngineIntelligenceWarningCode code = mock(EngineIntelligenceWarningCode.class);
        when(code.toString()).thenReturn(codeName);
        EngineIntelligenceWarningSummary warning = mock(EngineIntelligenceWarningSummary.class);
        when(warning.code()).thenReturn(code);
        when(warning.count()).thenReturn(1);
        return warning;
    }

    private void assertOversized(EngineIntelligenceSummary source) {
        assertValidation(source, EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
    }

    private void assertValidation(
            EngineIntelligenceSummary source,
            EngineIntelligenceProjectionOmissionReason reason
    ) {
        assertThatThrownBy(() -> policy.validatedCopy(source))
                .isInstanceOf(EngineIntelligenceProjectionValidationException.class)
                .hasMessage(reason.name());
    }
}
