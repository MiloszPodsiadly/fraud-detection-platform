package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceDiagnosticSignalResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceDiagnosticSignalReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceEngineReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceWarningReadModel;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceResponseMapperTest {

    private final EngineIntelligenceResponseMapper mapper = new EngineIntelligenceResponseMapper();
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void mapsMissingEngineIntelligenceToAbsentResponse() {
        EngineIntelligenceReadModel notProjected = EngineIntelligenceReadModel.notProjected("txn-old");
        EngineIntelligenceResponse response = mapper.toResponse(notProjected);

        assertThat(notProjected.available()).isFalse();
        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.ABSENT);
        assertThat(response.contractVersion()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.comparison()).isNull();
        assertThat(response.engines()).isEmpty();
        assertThat(response.diagnosticSignals()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void mapsAvailableRulesAndMlIntelligenceWithoutChangingComparison() {
        EngineIntelligenceResponse response = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.DISAGREEMENT,
                List.of()
        ));

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.contractVersion()).isEqualTo(1);
        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        assertThat(response.engines()).extracting("engineId").containsExactly("rules.primary", "ml.python.primary");
        assertThat(response.engines()).extracting("status")
                .containsExactly(EngineIntelligenceEngineStatusResponse.AVAILABLE, EngineIntelligenceEngineStatusResponse.AVAILABLE);
        assertThat(response.diagnosticSignals()).hasSize(1);
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void preservesProjectedComparisonValuesExactlyWithoutCalculatingThem() {
        EngineIntelligenceResponse response = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.DISAGREEMENT,
                EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                EngineIntelligenceScoreDeltaBucket.LARGE,
                List.of()
        ));

        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        assertThat(response.comparison().riskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
        assertThat(response.comparison().scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.LARGE);
    }

    @Test
    void publicComparisonResponseRejectsPartialIdentity() {
        assertThatThrownBy(() -> new EngineIntelligenceComparisonResponse(
                com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType.RULES_VS_ML,
                null,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        )).hasMessage("comparedEngineIds is required");
    }

    @Test
    void publicEngineResponseRejectsAvailableWithoutRiskLevel() {
        assertThatThrownBy(() -> new EngineIntelligenceEngineResponse(
                "rules.primary",
                FraudEngineType.RULES,
                EngineIntelligenceEngineStatusResponse.AVAILABLE,
                null,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_VELOCITY")
        )).hasMessage("ENGINE_INTELLIGENCE_AVAILABLE_STATUS_RISK_LEVEL_REQUIRED");
    }

    @Test
    void publicDiagnosticSignalResponseRejectsAvailableFraudSignalWithoutRiskLevel() {
        assertThatThrownBy(() -> new EngineIntelligenceDiagnosticSignalResponse(
                "rules.primary",
                FraudEngineType.RULES,
                EngineIntelligenceEngineStatusResponse.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                null,
                EngineIntelligenceScoreBucket.HIGH,
                "HIGH_VELOCITY"
        )).hasMessage("ENGINE_INTELLIGENCE_FRAUD_SIGNAL_RISK_LEVEL_REQUIRED");
    }

    @Test
    void unavailableEngineRemainsVisibleAndDegradesTopLevelStatus() {
        EngineIntelligenceResponse unavailable = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.UNAVAILABLE,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                List.of()
        ));

        assertThat(unavailable.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(unavailable.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.UNAVAILABLE);
    }

    @Test
    void timeoutRemainsVisibleAndDegradesTopLevelStatus() {
        EngineIntelligenceResponse timeout = mapper.toResponse(readModel(
                FraudEngineStatus.TIMEOUT,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                List.of()
        ));

        assertThat(timeout.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(timeout.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.TIMEOUT);
    }

    @Test
    void skippedMapsToNotApplicableWithoutDegradingByItself() {
        EngineIntelligenceResponse skipped = mapper.toResponse(readModel(
                FraudEngineStatus.SKIPPED,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                List.of()
        ));

        assertThat(skipped.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(skipped.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE);
    }

    @Test
    void unavailableResponseIsExplicitAndEmpty() {
        EngineIntelligenceResponse response = mapper.unavailable();

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.engines()).isEmpty();
        assertThat(response.diagnosticSignals()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void absentAndUnavailableResponsesSerializeExplicitNullFields() throws Exception {
        String absent = objectMapper.writeValueAsString(EngineIntelligenceResponse.absent());
        String unavailable = objectMapper.writeValueAsString(EngineIntelligenceResponse.unavailable());

        assertThat(absent).contains(
                "\"status\":\"ABSENT\"",
                "\"contractVersion\":null",
                "\"generatedAt\":null",
                "\"comparison\":null",
                "\"engines\":[]",
                "\"diagnosticSignals\":[]",
                "\"warnings\":[]"
        );
        assertThat(unavailable).contains(
                "\"status\":\"UNAVAILABLE\"",
                "\"contractVersion\":null",
                "\"generatedAt\":null",
                "\"comparison\":null",
                "\"engines\":[]",
                "\"diagnosticSignals\":[]",
                "\"warnings\":[]"
        );
    }

    @Test
    void limitsPublicProjectionArraysToOpenApiBounds() {
        EngineIntelligenceResponse response = mapper.toResponse(readModelWithManyValues());

        assertThat(response.engines()).hasSize(3);
        assertThat(response.engines().getFirst().reasonCodes()).hasSize(5);
        assertThat(response.diagnosticSignals()).hasSize(5);
        assertThat(response.warnings()).hasSize(10);
    }

    @Test
    void mapsSharedThreeEngineGoldenFixtureToPublicResponse() throws Exception {
        EngineIntelligenceSummary summary = objectMapper.readValue(
                Files.readString(goldenFixturePath()),
                EngineIntelligenceSummary.class
        );

        EngineIntelligenceResponse response = mapper.toResponse(readModelFrom(summary));

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.engines()).extracting("engineId")
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
        assertThat(response.diagnosticSignals()).extracting("engineId").containsExactly("velocity.primary");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void warningsFallbackAndEmptyEnginesDegradePublicExposureStatus() {
        EngineIntelligenceResponse warning = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                List.of(new EngineIntelligenceWarningReadModel(EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED, 1))
        ));
        EngineIntelligenceResponse fallback = mapper.toResponse(readModel(
                FraudEngineStatus.FALLBACK_USED,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.PARTIAL,
                List.of()
        ));
        EngineIntelligenceResponse emptyEngines = mapper.toResponse(EngineIntelligenceReadModel.projected(
                "txn-empty",
                1,
                Instant.parse("2026-06-18T10:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of(),
                List.of()
        ));

        assertThat(warning.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(fallback.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(fallback.engines()).extracting("status").contains(EngineIntelligenceEngineStatusResponse.DEGRADED);
        assertThat(emptyEngines.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
    }

    @Test
    void publicResponseDoesNotExposeRawInternalPayloads() throws Exception {
        String serialized = objectMapper.writeValueAsString(mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.PARTIAL,
                List.of()
        )));

        assertThat(serialized).doesNotContain(
                "FraudEngineResult",
                "rawFeatureVector",
                "rawMlRequest",
                "rawMlResponse",
                "rawEvidence",
                "rawPayload",
                "groundTruth",
                "trainingLabel",
                "finalDecision",
                "stackTrace",
                "exceptionMessage",
                "modelPath",
                "secret",
                "token"
        );
    }

    private EngineIntelligenceReadModel readModel(
            FraudEngineStatus rulesStatus,
            FraudEngineStatus mlStatus,
            EngineIntelligenceAgreementStatus agreementStatus,
            List<EngineIntelligenceWarningReadModel> warnings
    ) {
        return readModel(
                rulesStatus,
                mlStatus,
                agreementStatus,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                warnings
        );
    }

    private EngineIntelligenceReadModel readModel(
            FraudEngineStatus rulesStatus,
            FraudEngineStatus mlStatus,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            List<EngineIntelligenceWarningReadModel> warnings
    ) {
        return EngineIntelligenceReadModel.projected(
                "txn-1",
                1,
                Instant.parse("2026-06-18T10:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        agreementStatus,
                        riskMismatchStatus,
                        scoreDeltaBucket
                ),
                List.of(
                        engine("rules.primary", FraudEngineType.RULES, rulesStatus, List.of("HIGH_VELOCITY")),
                        engine("ml.python.primary", FraudEngineType.ML_MODEL, mlStatus, List.of("ML_MODEL_SIGNAL"))
                ),
                diagnosticSignals(rulesStatus),
                warnings
        );
    }

    private List<EngineIntelligenceDiagnosticSignalReadModel> diagnosticSignals(FraudEngineStatus rulesStatus) {
        if (rulesStatus == FraudEngineStatus.AVAILABLE) {
            return List.of(new EngineIntelligenceDiagnosticSignalReadModel(
                    "rules.primary",
                    FraudEngineType.RULES,
                    rulesStatus,
                    EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                    RiskLevel.HIGH,
                    EngineIntelligenceScoreBucket.HIGH,
                    "HIGH_VELOCITY"
            ));
        }
        return List.of(new EngineIntelligenceDiagnosticSignalReadModel(
                "rules.primary",
                FraudEngineType.RULES,
                rulesStatus,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE,
                "ORCHESTRATOR_ENGINE_TIMEOUT"
        ));
    }

    private EngineIntelligenceReadModel readModelWithManyValues() {
        List<String> reasonCodes = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> "HIGH_VELOCITY")
                .toList();
        List<EngineIntelligenceEngineReadModel> engines = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> engine("rules.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, reasonCodes))
                .toList();
        List<EngineIntelligenceDiagnosticSignalReadModel> diagnosticSignals = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new EngineIntelligenceDiagnosticSignalReadModel(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        "HIGH_VELOCITY"
                ))
                .toList();
        List<EngineIntelligenceWarningReadModel> warnings = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new EngineIntelligenceWarningReadModel(
                        EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED,
                        index
                ))
                .toList();
        return EngineIntelligenceReadModel.projected(
                "txn-many",
                1,
                Instant.parse("2026-06-18T10:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                engines,
                diagnosticSignals,
                warnings
        );
    }

    private EngineIntelligenceReadModel readModelFrom(EngineIntelligenceSummary summary) {
        return EngineIntelligenceReadModel.projected(
                "txn-golden",
                summary.contractVersion(),
                summary.generatedAt(),
                new EngineIntelligenceComparisonReadModel(
                        summary.comparison().agreementStatus(),
                        summary.comparison().riskMismatchStatus(),
                        summary.comparison().scoreDeltaBucket()
                ),
                summary.engines().stream()
                        .map(engine -> new EngineIntelligenceEngineReadModel(
                                engine.engineId(),
                                engine.engineType(),
                                engine.status(),
                                engine.riskLevel(),
                                engine.scoreBucket(),
                                engine.reasonCodes()
                        ))
                        .toList(),
                summary.diagnosticSignals().stream()
                        .map(signal -> new EngineIntelligenceDiagnosticSignalReadModel(
                                signal.engineId(),
                                signal.engineType(),
                                signal.engineStatus(),
                                signal.signalCategory(),
                                signal.riskLevel(),
                                signal.scoreBucket(),
                                signal.reasonCode()
                        ))
                        .toList(),
                summary.warnings().stream()
                        .map(warning -> new EngineIntelligenceWarningReadModel(warning.code(), warning.count()))
                        .toList()
        );
    }

    private Path goldenFixturePath() {
        Path fromRoot = Path.of(
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/engine_intelligence_three_engine_golden.json"
        );
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of(
                "..",
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/engine_intelligence_three_engine_golden.json"
        );
    }

    private EngineIntelligenceEngineReadModel engine(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            List<String> reasonCodes
    ) {
        return new EngineIntelligenceEngineReadModel(
                engineId,
                engineType,
                status,
                status == FraudEngineStatus.AVAILABLE ? RiskLevel.HIGH : null,
                status == FraudEngineStatus.AVAILABLE ? EngineIntelligenceScoreBucket.HIGH : EngineIntelligenceScoreBucket.UNAVAILABLE,
                reasonCodes
        );
    }
}
