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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

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
                EngineIntelligenceAgreementStatus.AGREEMENT,
                List.of()
        ));

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.contractVersion()).isEqualTo(1);
        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.AGREEMENT);
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
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.NONE,
                List.of()
        ));

        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.AGREEMENT);
        assertThat(response.comparison().riskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL);
        assertThat(response.comparison().scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.NONE);
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
                EngineIntelligenceAgreementStatus.PARTIAL,
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
                EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE,
                List.of()
        ));

        assertThat(timeout.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(timeout.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.TIMEOUT);
    }

    @Test
    void requiredRulesSkippedMapsToNotApplicableAndDegradesTopLevelStatus() {
        EngineIntelligenceResponse skipped = mapper.toResponse(readModel(
                FraudEngineStatus.SKIPPED,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE,
                List.of()
        ));

        assertThat(skipped.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(skipped.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE);
    }

    @Test
    void requiredMlSkippedDegradesTopLevelStatus() {
        EngineIntelligenceResponse skipped = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.SKIPPED,
                EngineIntelligenceAgreementStatus.PARTIAL,
                List.of()
        ));

        assertThat(skipped.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(skipped.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE);
    }

    @Test
    void optionalVelocitySkippedOrAbsentDoesNotDegradeHealthyRequiredEngines() {
        EngineIntelligenceResponse absentVelocity = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                List.of()
        ));
        EngineIntelligenceResponse skippedVelocity = mapper.toResponse(readModelWithVelocity(
                FraudEngineStatus.SKIPPED,
                List.of()
        ));

        assertThat(absentVelocity.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(skippedVelocity.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(skippedVelocity.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE);
    }

    @Test
    void optionalVelocityAvailableKeepsAvailableAndOperationalFailureDegrades() {
        assertThat(mapper.toResponse(readModelWithVelocity(FraudEngineStatus.AVAILABLE, List.of())).status())
                .isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(mapper.toResponse(readModelWithVelocity(FraudEngineStatus.DEGRADED, List.of())).status())
                .isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(mapper.toResponse(readModelWithVelocity(FraudEngineStatus.TIMEOUT, List.of())).status())
                .isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
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
        assertThat(response.diagnosticSignals()).extracting("engineId")
                .containsExactly("velocity.primary", "velocity.primary", "rules.primary", "ml.python.primary");
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
                EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE,
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
        assertThat(emptyEngines.status()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
    }

    @Test
    void constructorRejectsContradictoryProjectedStatus() {
        EngineIntelligenceResponse healthy = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                List.of()
        ));
        EngineIntelligenceResponse warning = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                List.of(new EngineIntelligenceWarningReadModel(EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED, 1))
        ));
        EngineIntelligenceResponse degradedRules = mapper.toResponse(readModel(
                FraudEngineStatus.DEGRADED,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE,
                List.of()
        ));
        EngineIntelligenceResponse timeoutMl = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.TIMEOUT,
                EngineIntelligenceAgreementStatus.PARTIAL,
                List.of()
        ));

        assertStatusRejected(EngineIntelligenceResponseStatus.AVAILABLE, warning);
        assertStatusRejected(EngineIntelligenceResponseStatus.AVAILABLE, degradedRules);
        assertStatusRejected(EngineIntelligenceResponseStatus.AVAILABLE, timeoutMl);
        assertStatusRejected(EngineIntelligenceResponseStatus.DEGRADED, healthy);
        assertStatusRejected(EngineIntelligenceResponseStatus.ABSENT, healthy);
        assertStatusRejected(EngineIntelligenceResponseStatus.UNAVAILABLE, healthy);
    }

    @Test
    void publicResponseRejectsSharedInvalidStatusFixtures() throws Exception {
        JsonNode cases = objectMapper.readTree(fixturePath("invalid_semantic_cases.json").toFile()).get("cases");

        for (JsonNode semanticCase : StreamSupport.stream(cases.spliterator(), false).toList()) {
            if (!"engine-intelligence-response".equals(semanticCase.get("category").textValue())) {
                continue;
            }

            assertThatThrownBy(() -> objectMapper.readValue(
                    objectMapper.writeValueAsString(semanticCase.get("engineIntelligenceResponse")),
                    EngineIntelligenceResponse.class
            ))
                    .as(semanticCase.get("caseId").textValue())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void publicResponseDoesNotExposeRawInternalPayloads() throws Exception {
        String serialized = objectMapper.writeValueAsString(mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.AGREEMENT,
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
                comparisonRiskMismatch(rulesStatus, mlStatus),
                comparisonScoreDelta(rulesStatus, mlStatus),
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
                        engine("rules.primary", FraudEngineType.RULES, rulesStatus, reasonCodesFor(FraudEngineType.RULES, rulesStatus)),
                        engine("ml.python.primary", FraudEngineType.ML_MODEL, mlStatus, reasonCodesFor(FraudEngineType.ML_MODEL, mlStatus))
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

    private List<String> reasonCodesFor(FraudEngineType engineType, FraudEngineStatus status) {
        if (status == FraudEngineStatus.AVAILABLE) {
            return engineType == FraudEngineType.ML_MODEL ? List.of("ML_MODEL_SIGNAL") : List.of("HIGH_VELOCITY");
        }
        return engineType == FraudEngineType.ML_MODEL ? List.of("ML_MODEL_TIMEOUT") : List.of("ORCHESTRATOR_ENGINE_TIMEOUT");
    }

    private EngineIntelligenceReadModel readModelWithManyValues() {
        List<String> reasonCodes = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> "HIGH_VELOCITY")
                .toList();
        List<EngineIntelligenceEngineReadModel> engines = new java.util.ArrayList<>();
        engines.add(engine("rules.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, reasonCodes));
        engines.add(engine("ml.python.primary", FraudEngineType.ML_MODEL, FraudEngineStatus.AVAILABLE, List.of("ML_MODEL_SIGNAL")));
        engines.add(engine("velocity.primary", FraudEngineType.VELOCITY, FraudEngineStatus.AVAILABLE, List.of("RAPID_TRANSFER_BURST_SIGNAL")));
        java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> engine("velocity.primary", FraudEngineType.VELOCITY, FraudEngineStatus.AVAILABLE, List.of("RAPID_TRANSFER_BURST_SIGNAL")))
                .forEach(engines::add);
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
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.NONE
                ),
                engines,
                diagnosticSignals,
                warnings
        );
    }

    private EngineIntelligenceReadModel readModelWithVelocity(
            FraudEngineStatus velocityStatus,
            List<EngineIntelligenceWarningReadModel> warnings
    ) {
        return EngineIntelligenceReadModel.projected(
                "txn-velocity",
                1,
                Instant.parse("2026-06-18T10:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.NONE
                ),
                List.of(
                        engine("rules.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, List.of("HIGH_VELOCITY")),
                        engine("ml.python.primary", FraudEngineType.ML_MODEL, FraudEngineStatus.AVAILABLE, List.of("ML_MODEL_SIGNAL")),
                        engine("velocity.primary", FraudEngineType.VELOCITY, velocityStatus, reasonCodesFor(FraudEngineType.VELOCITY, velocityStatus))
                ),
                List.of(new EngineIntelligenceDiagnosticSignalReadModel(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        "HIGH_VELOCITY"
                )),
                warnings
        );
    }

    private void assertStatusRejected(
            EngineIntelligenceResponseStatus status,
            EngineIntelligenceResponse source
    ) {
        assertThatThrownBy(() -> new EngineIntelligenceResponse(
                status,
                source.contractVersion(),
                source.generatedAt(),
                source.comparison(),
                source.engines(),
                source.diagnosticSignals(),
                source.warnings()
        )).isInstanceOf(IllegalArgumentException.class);
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
        return fixturePath("engine_intelligence_three_engine_golden.json");
    }

    private Path fixturePath(String fixtureName) {
        Path fromRoot = Path.of(
                "common-events",
                "src/test/resources/fixtures/engine-intelligence",
                fixtureName
        );
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of(
                "..",
                "common-events",
                "src/test/resources/fixtures/engine-intelligence",
                fixtureName
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

    private EngineIntelligenceRiskMismatchStatus comparisonRiskMismatch(FraudEngineStatus rulesStatus, FraudEngineStatus mlStatus) {
        return rulesStatus == FraudEngineStatus.AVAILABLE && mlStatus == FraudEngineStatus.AVAILABLE
                ? EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL
                : EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE;
    }

    private EngineIntelligenceScoreDeltaBucket comparisonScoreDelta(FraudEngineStatus rulesStatus, FraudEngineStatus mlStatus) {
        return rulesStatus == FraudEngineStatus.AVAILABLE && mlStatus == FraudEngineStatus.AVAILABLE
                ? EngineIntelligenceScoreDeltaBucket.NONE
                : EngineIntelligenceScoreDeltaBucket.UNAVAILABLE;
    }
}
