package com.frauddetection.alert.engineintelligence;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineIntelligenceReadModelMapperTest {

    private static final List<String> FORBIDDEN_INTERNAL_FIELDS = List.of(
            "rawEvidence", "rawContribution", "featureSnapshot", "featureVector", "rawPayload", "payload",
            "endpoint", "token", "secret", "stacktrace", "exceptionMessage", "internalAggregation",
            "FraudEngineAggregationResult", "NormalizedFraudEngineResult", "ScoringContext", "rawMlResponse",
            "_id", "createdAt", "updatedAt", "EngineIntelligenceProjection"
    );
    private static final List<String> FORBIDDEN_DECISION_FIELDS = List.of(
            "finalDecision", "recommendedAction", "approve", "decline", "block", "winningEngine",
            "platformRiskScore", "paymentAuthorization"
    );

    private final EngineIntelligenceReadModelMapper mapper = new EngineIntelligenceReadModelMapper();
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void mapsProjectionToBoundedReadModel() {
        EngineIntelligenceReadModel response = mapper.map(fullProjection());

        assertThat(response.transactionId()).isEqualTo("txn-fdp96-001");
        assertThat(response.available()).isTrue();
        assertThat(response.contractVersion()).isEqualTo(1);
        assertThat(response.generatedAt()).isEqualTo(EngineIntelligenceProjectionTestFixtures.GENERATED_AT);
        assertThat(response.engineCount()).isEqualTo(2);
        assertThat(response.diagnosticSignalCount()).isEqualTo(2);
        assertThat(response.warningCount()).isEqualTo(2);
    }

    @Test
    void mapsComparisonAsAgreementStatusRiskMismatchAndScoreDelta() {
        var comparison = mapper.map(fullProjection()).comparison();

        assertThat(comparison.agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.PARTIAL);
        assertThat(comparison.riskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE);
        assertThat(comparison.scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.UNAVAILABLE);
    }

    @Test
    void doesNotExposeMongoMetadataRawInternalFieldsOrProjectionClassNames() throws Exception {
        String serialized = objectMapper.writeValueAsString(mapper.map(fullProjection()));

        assertThat(serialized).doesNotContain(FORBIDDEN_INTERNAL_FIELDS.toArray(String[]::new));
    }

    @Test
    void doesNotExposeDecisioningFields() throws Exception {
        String serialized = objectMapper.writeValueAsString(mapper.map(fullProjection()));

        assertThat(serialized).doesNotContain(FORBIDDEN_DECISION_FIELDS.toArray(String[]::new));
    }

    @Test
    void timeoutEngineHasRiskLevelNull() {
        var timeoutEngine = mapper.map(fullProjection()).engines().get(1);

        assertThat(timeoutEngine.status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(timeoutEngine.riskLevel()).isNull();
    }

    @Test
    void unavailableEngineHasRiskLevelNull() {
        var unavailableEngine = mapper.map(projectionWithStatus(FraudEngineStatus.UNAVAILABLE)).engines().getFirst();

        assertThat(unavailableEngine.riskLevel()).isNull();
    }

    @Test
    void degradedEngineDoesNotBecomeLowRisk() {
        var degradedEngine = mapper.map(projectionWithStatus(FraudEngineStatus.DEGRADED)).engines().getFirst();

        assertThat(degradedEngine.riskLevel()).isNull();
        assertThat(degradedEngine.scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void operationalSignalHasRiskLevelNull() {
        var operationalSignal = mapper.map(fullProjection()).diagnosticSignals().get(1);

        assertThat(operationalSignal.signalCategory()).isEqualTo(EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL);
        assertThat(operationalSignal.riskLevel()).isNull();
    }

    @Test
    void diagnosticDisagreementDoesNotCreateDecisioningFields() throws Exception {
        EngineIntelligenceProjection projection = new EngineIntelligenceProjectionMapper(
                new EngineIntelligenceProjectionPolicy()
        ).map(
                "txn-disagreement",
                EngineIntelligenceProjectionTestFixtures.disagreementSummary(),
                null
        ).projection().orElseThrow();

        EngineIntelligenceReadModel response = mapper.map(projection);
        String serialized = objectMapper.writeValueAsString(response);

        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        assertThat(serialized).doesNotContain(FORBIDDEN_DECISION_FIELDS.toArray(String[]::new));
    }

    @Test
    void rejectsProjectionThatExceedsBoundedResponseSize() {
        Instant now = EngineIntelligenceProjectionTestFixtures.GENERATED_AT;
        var engine = new EngineIntelligenceEngineProjection(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                null,
                EngineIntelligenceScoreBucket.NONE,
                List.of()
        );
        EngineIntelligenceProjection oversized = new EngineIntelligenceProjection(
                "txn-oversized",
                1,
                now,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                List.of(engine, engine, engine),
                List.of(),
                List.of(),
                now,
                now
        );

        assertThatThrownBy(() -> mapper.map(oversized))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class)
                .hasMessage("Engine intelligence projection is temporarily unavailable.");
    }

    @Test
    void missingProjectionReadModelSerializesAsBoundedAvailabilityResponse() throws Exception {
        String serialized = objectMapper.writeValueAsString(EngineIntelligenceReadModel.notProjected("txn-old"));

        assertThat(serialized).isEqualTo("{\"transactionId\":\"txn-old\",\"available\":false,\"reason\":\"NOT_PROJECTED\"}");
    }

    @Test
    void corruptedProjectionWithRawEvidenceReasonCodeIsNotExposed() {
        assertRejected(
                projectionWithEngine("rules.primary", FraudEngineStatus.AVAILABLE, null, List.of("rawEvidence")),
                "rawEvidence"
        );
    }

    @Test
    void corruptedProjectionWithFeatureSnapshotEngineIdIsNotExposed() {
        assertRejected(
                projectionWithEngine("featureSnapshot", FraudEngineStatus.AVAILABLE, null, List.of()),
                "featureSnapshot"
        );
    }

    @Test
    void corruptedProjectionWithEndpointTokenSecretWarningIsNotExposed() {
        EngineIntelligenceWarningProjection warning = mock(EngineIntelligenceWarningProjection.class);
        when(warning.warningCode()).thenThrow(new IllegalStateException("endpoint token secret warning"));

        assertRejected(projection(List.of(), List.of(), List.of(warning)), "endpoint", "token", "secret");
    }

    @Test
    void corruptedProjectionWithOverlongEngineIdIsRejected() {
        assertRejected(
                projectionWithEngine("x".repeat(129), FraudEngineStatus.AVAILABLE, null, List.of()),
                "x".repeat(129)
        );
    }

    @Test
    void corruptedProjectionWithOverlongReasonCodeIsRejected() {
        assertRejected(
                projectionWithEngine("rules.primary", FraudEngineStatus.AVAILABLE, null, List.of("x".repeat(129))),
                "x".repeat(129)
        );
    }

    @Test
    void corruptedProjectionWithControlCharacterStringIsRejected() {
        assertRejected(
                projectionWithEngine("rules.primary", FraudEngineStatus.AVAILABLE, null, List.of("HIGH\nVELOCITY")),
                "HIGH\nVELOCITY"
        );
    }

    @Test
    void corruptedOperationalTimeoutWithRiskLevelIsRejectedOrSanitized() {
        assertRejected(
                projectionWithEngine("ml.python.primary", FraudEngineStatus.TIMEOUT, com.frauddetection.common.events.enums.RiskLevel.LOW, List.of("ML_MODEL_TIMEOUT")),
                "LOW"
        );
    }

    private EngineIntelligenceProjection fullProjection() {
        return new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                .map("txn-fdp96-001", EngineIntelligenceProjectionTestFixtures.fullSummary(), null)
                .projection()
                .orElseThrow();
    }

    private EngineIntelligenceProjection projectionWithStatus(FraudEngineStatus status) {
        Instant now = EngineIntelligenceProjectionTestFixtures.GENERATED_AT;
        return new EngineIntelligenceProjection(
                "txn-fdp96-operational",
                1,
                now,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                List.of(new EngineIntelligenceEngineProjection(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        status,
                        null,
                        EngineIntelligenceScoreBucket.UNAVAILABLE,
                        List.of("ML_MODEL_TIMEOUT")
                )),
                List.of(),
                List.of(),
                now,
                now
        );
    }

    private EngineIntelligenceProjection projectionWithEngine(
            String engineId,
            FraudEngineStatus status,
            com.frauddetection.common.events.enums.RiskLevel riskLevel,
            List<String> reasonCodes
    ) {
        return projection(
                List.of(new EngineIntelligenceEngineProjection(
                        engineId,
                        "ml.python.primary".equals(engineId) ? FraudEngineType.ML_MODEL : FraudEngineType.RULES,
                        status,
                        riskLevel,
                        status == FraudEngineStatus.AVAILABLE
                                ? EngineIntelligenceScoreBucket.NONE
                                : EngineIntelligenceScoreBucket.UNAVAILABLE,
                        reasonCodes
                )),
                List.of(),
                List.of()
        );
    }

    private EngineIntelligenceProjection projection(
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals,
            List<EngineIntelligenceWarningProjection> warnings
    ) {
        Instant now = EngineIntelligenceProjectionTestFixtures.GENERATED_AT;
        return new EngineIntelligenceProjection(
                "txn-corrupted",
                1,
                now,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                engines,
                diagnosticSignals,
                warnings,
                now,
                now
        );
    }

    private void assertRejected(EngineIntelligenceProjection projection, String... rawValues) {
        var assertion = assertThatThrownBy(() -> mapper.map(projection))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class)
                .hasMessage("Engine intelligence projection is temporarily unavailable.");
        for (String rawValue : rawValues) {
            assertion.hasMessageNotContaining(rawValue);
        }
    }
}
