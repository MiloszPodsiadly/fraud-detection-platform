package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineIntelligenceProjectionMapperTest {

    private static final Instant NOW = Instant.parse("2026-06-02T08:00:00Z");

    private final EngineIntelligenceProjectionMapper mapper = new EngineIntelligenceProjectionMapper(
            new EngineIntelligenceProjectionPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void nullEngineIntelligenceReturnsEmptyProjection() {
        assertOmitted(
                mapper.map("txn-1", null, null),
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
        );
    }

    @Test
    void minimalEngineIntelligenceMapsToProjection() {
        EngineIntelligenceProjection projection = mapped(EngineIntelligenceProjectionTestFixtures.minimalSummary());

        assertThat(projection.getContractVersion()).isEqualTo(1);
        assertThat(projection.getEngineCount()).isEqualTo(1);
        assertThat(projection.getDiagnosticSignalCount()).isZero();
        assertThat(projection.getWarningCount()).isZero();
    }

    @Test
    void fullBoundedEngineIntelligenceMapsToProjection() {
        EngineIntelligenceProjection projection = mapped(EngineIntelligenceProjectionTestFixtures.fullSummary());

        assertThat(projection.getEngines()).hasSize(2);
        assertThat(projection.getDiagnosticSignals()).hasSize(2);
        assertThat(projection.getWarnings()).hasSize(2);
    }

    @Test
    void unsupportedContractVersionIsOmittedBoundedly() {
        EngineIntelligenceSummary summary = summaryMock();
        when(summary.contractVersion()).thenReturn(2);

        assertOmitted(
                mapper.map("txn-1", summary, null),
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION
        );
    }

    @Test
    void oversizedPayloadIsOmittedBoundedly() {
        EngineIntelligenceSummary summary = summaryMock();
        when(summary.engines()).thenReturn(Collections.nCopies(3, EngineIntelligenceProjectionTestFixtures.timeoutMl()));

        assertOmitted(
                mapper.map("txn-1", summary, null),
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED
        );
    }

    @Test
    void unsupportedReasonCodeIsDroppedOrOmittedBoundedly() {
        EngineIntelligenceSummary summary = summaryMock();
        EngineIntelligenceEngineResult engine = mock(EngineIntelligenceEngineResult.class);
        when(engine.engineId()).thenReturn("rules.primary");
        when(engine.engineType()).thenReturn(FraudEngineType.RULES);
        when(engine.status()).thenReturn(FraudEngineStatus.AVAILABLE);
        when(engine.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(engine.scoreBucket()).thenReturn(EngineIntelligenceScoreBucket.HIGH);
        when(engine.reasonCodes()).thenReturn(List.of("NOT_ALLOWLISTED"));
        when(summary.engines()).thenReturn(List.of(engine));

        assertOmitted(
                mapper.map("txn-1", summary, null),
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED
        );
    }

    @Test
    void rawEvidenceIsNotMapped() {
        assertProjectionModelDoesNotDeclare("rawEvidence", "evidenceDescription", "evidenceTitle");
    }

    @Test
    void rawContributionIsNotMapped() {
        assertProjectionModelDoesNotDeclare("rawContribution", "contributionValue");
    }

    @Test
    void internalAggregationTypeIsNotMapped() {
        assertProjectionModelDoesNotDeclare("aggregation", "normalizedFraudEngineResult", "fraudEngineAggregationResult");
    }

    @Test
    void operationalEngineStatusMapsWithoutRiskLevel() {
        EngineIntelligenceProjection projection = mapped(EngineIntelligenceProjectionTestFixtures.fullSummary());

        assertThat(projection.getEngines())
                .filteredOn(engine -> engine.status() == FraudEngineStatus.TIMEOUT)
                .singleElement()
                .satisfies(engine -> assertThat(engine.riskLevel()).isNull());
    }

    @Test
    void operationalDiagnosticSignalMapsWithoutRiskLevel() {
        EngineIntelligenceProjection projection = mapped(EngineIntelligenceProjectionTestFixtures.fullSummary());

        assertThat(projection.getDiagnosticSignals())
                .filteredOn(signal -> signal.engineStatus() == FraudEngineStatus.TIMEOUT)
                .singleElement()
                .satisfies(signal -> assertThat(signal.riskLevel()).isNull());
    }

    @Test
    void diagnosticDisagreementMapsAsDiagnosticOnly() {
        EngineIntelligenceProjection projection = mapped(EngineIntelligenceProjectionTestFixtures.disagreementSummary());

        assertThat(projection.getComparisonStatus()).isEqualTo(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        assertThat(projection.getRiskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
        assertThat(projection.getScoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.LARGE);
        assertProjectionModelDoesNotDeclare("finalDecision", "recommendedAction", "alertSeverity");
    }

    private EngineIntelligenceProjection mapped(EngineIntelligenceSummary summary) {
        return mapper.map("txn-1", summary, null).projection().orElseThrow();
    }

    private EngineIntelligenceSummary summaryMock() {
        EngineIntelligenceSummary source = mock(EngineIntelligenceSummary.class);
        when(source.contractVersion()).thenReturn(EngineIntelligenceSummary.CONTRACT_VERSION);
        when(source.generatedAt()).thenReturn(EngineIntelligenceProjectionTestFixtures.GENERATED_AT);
        when(source.engines()).thenReturn(List.of());
        when(source.comparison()).thenReturn(EngineIntelligenceProjectionTestFixtures.comparison(
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        ));
        when(source.diagnosticSignals()).thenReturn(List.of());
        when(source.warnings()).thenReturn(List.of());
        return source;
    }

    private void assertOmitted(
            EngineIntelligenceProjectionResult result,
            EngineIntelligenceProjectionOmissionReason reason
    ) {
        assertThat(result.projection()).isEmpty();
        assertThat(result.omissionReason()).contains(reason);
    }

    private void assertProjectionModelDoesNotDeclare(String... forbiddenFields) {
        assertThat(Arrays.stream(EngineIntelligenceProjection.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain(forbiddenFields);
    }
}
