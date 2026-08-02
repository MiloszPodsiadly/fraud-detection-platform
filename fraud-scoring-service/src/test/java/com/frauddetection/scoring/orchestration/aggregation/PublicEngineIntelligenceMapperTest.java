package com.frauddetection.scoring.orchestration.aggregation;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicEngineIntelligenceMapperTest {
    private final FraudEngineAggregationService service =
            new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy());
    private final PublicEngineIntelligenceMapper mapper = new PublicEngineIntelligenceMapper();

    @Test
    void mapsAgreementWithoutDecisioning() {
        var comparison = map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.HIGH).comparison();

        assertThat(comparison.comparisonType()).isEqualTo(EngineIntelligenceComparisonType.RULES_VS_ML);
        assertThat(comparison.comparedEngineIds()).containsExactly("rules.primary", "ml.python.primary");
        assertThat(comparison.agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.AGREEMENT);
    }

    @Test
    void mapsAdjacentVarianceWithoutDecisioning() {
        var summary = map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.MEDIUM);

        assertThat(summary.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.ADJACENT_RISK_VARIANCE);
        assertThat(summary.comparison().riskMismatchStatus()).isEqualTo(EngineIntelligenceRiskMismatchStatus.ADJACENT_RISK_LEVEL);
    }

    @Test
    void mapsDisagreementWithoutDecline() throws Exception {
        String json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(map(0.9d, RiskLevel.CRITICAL, 0.1d, RiskLevel.LOW));

        assertThat(json).contains("DISAGREEMENT").doesNotContainIgnoringCase("decline", "approve");
    }

    @Test
    void mapsTimeoutAsUnavailableScoreBucket() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable(
                        "ml.python.primary",
                        FraudEngineStatus.TIMEOUT,
                        "ORCHESTRATOR_ENGINE_TIMEOUT"
                )
        )));

        assertThat(summary.engines().get(1).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void mapsAggregationCompatibleWithSharedThreeEngineGoldenFixture() throws Exception {
        EngineIntelligenceSummary expected = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .readValue(Files.readString(goldenFixturePath()), EngineIntelligenceSummary.class);
        EngineIntelligenceSummary actual = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.75d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", 0.25d, RiskLevel.LOW, "LOW_MODEL_RISK"),
                AggregationTestSupport.available(
                        "velocity.primary",
                        0.95d,
                        RiskLevel.CRITICAL,
                        "RAPID_PLN_20K_BURST",
                        "TRANSACTION_VELOCITY"
                )
        )));

        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
        assertThat(actual.comparison().scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.LARGE);
    }

    @Test
    void mapsScoreDeltaBucketFromExactAggregationDeltaNotPublicScoreBuckets() {
        EngineIntelligenceSummary samePublicBucketMediumDelta =
                map(0.26d, RiskLevel.HIGH, 0.50d, RiskLevel.HIGH);
        EngineIntelligenceSummary lowHighLargeDelta =
                map(0.10d, RiskLevel.HIGH, 0.60d, RiskLevel.LOW);

        assertThat(samePublicBucketMediumDelta.engines()).extracting(EngineIntelligenceEngineResult::scoreBucket)
                .containsExactly(EngineIntelligenceScoreBucket.MEDIUM, EngineIntelligenceScoreBucket.MEDIUM);
        assertThat(samePublicBucketMediumDelta.comparison().scoreDeltaBucket())
                .isEqualTo(EngineIntelligenceScoreDeltaBucket.MEDIUM);
        assertThat(lowHighLargeDelta.engines()).extracting(EngineIntelligenceEngineResult::scoreBucket)
                .containsExactly(EngineIntelligenceScoreBucket.LOW, EngineIntelligenceScoreBucket.HIGH);
        assertThat(lowHighLargeDelta.comparison().scoreDeltaBucket())
                .isEqualTo(EngineIntelligenceScoreDeltaBucket.LARGE);
    }

    @Test
    void mapsExactComparableDeltaBoundaries() {
        assertScoreDeltaBucket(0.50d, 0.50d, EngineIntelligenceScoreDeltaBucket.NONE);
        assertScoreDeltaBucket(0.15d, 0.00d, EngineIntelligenceScoreDeltaBucket.SMALL);
        assertScoreDeltaBucket(0.1501d, 0.00d, EngineIntelligenceScoreDeltaBucket.MEDIUM);
        assertScoreDeltaBucket(0.35d, 0.00d, EngineIntelligenceScoreDeltaBucket.MEDIUM);
        assertScoreDeltaBucket(0.3501d, 0.00d, EngineIntelligenceScoreDeltaBucket.LARGE);
        assertScoreDeltaBucket(1.00d, 0.00d, EngineIntelligenceScoreDeltaBucket.LARGE);
    }

    @Test
    void mapsOperationalRulesOrMlDeltaAsUnavailable() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable(
                        "ml.python.primary",
                        FraudEngineStatus.TIMEOUT,
                        "ML_MODEL_TIMEOUT"
                )
        )));

        assertThat(summary.comparison().scoreDeltaBucket()).isEqualTo(EngineIntelligenceScoreDeltaBucket.UNAVAILABLE);
    }

    @Test
    void rejectsAvailableRulesAndMlWhenAggregationDeltaIsUnavailable() {
        assertThatThrownBy(() -> mapper.map(new FraudEngineAggregationResult(
                requiredAvailableRulesAndMl(),
                FraudEngineAgreementStatus.AGREEMENT,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE, null),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.SAME_RISK_LEVEL),
                List.of(),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        ))).hasMessage("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
    }

    @Test
    void rejectsOperationalRulesOrMlWhenAggregationDeltaIsAvailable() {
        assertThatThrownBy(() -> mapper.map(new FraudEngineAggregationResult(
                List.of(
                        availableRules(),
                        AggregationTestSupport.normalized(
                                "ml.python.primary",
                                FraudEngineStatus.TIMEOUT,
                                null,
                                null,
                                "ML_MODEL_TIMEOUT"
                        )
                ),
                FraudEngineAgreementStatus.PARTIAL,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.AVAILABLE, 0.1d),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
    }

    @Test
    void mapsMissingScoreAsUnavailableNotZero() {
        FraudEngineAggregationResult result = resultWithNormalizedEngine(
                AggregationTestSupport.normalized("rules.primary", FraudEngineStatus.AVAILABLE, null, RiskLevel.HIGH, "HIGH_VELOCITY")
        );

        assertThat(mapper.map(result).engines().getFirst().scoreBucket())
                .isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE)
                .isNotEqualTo(EngineIntelligenceScoreBucket.LOW);
    }

    @Test
    void mapsOperationalEngineRiskLevelToNull() {
        assertMappedEngineRiskLevelIsNull(FraudEngineStatus.SKIPPED, RiskLevel.HIGH);
    }

    @Test
    void mapsTimeoutEngineRiskLevelToNull() {
        assertMappedEngineRiskLevelIsNull(FraudEngineStatus.TIMEOUT, RiskLevel.LOW);
    }

    @Test
    void mapsUnavailableEngineRiskLevelToNull() {
        assertMappedEngineRiskLevelIsNull(FraudEngineStatus.UNAVAILABLE, RiskLevel.HIGH);
    }

    @Test
    void mapsDegradedEngineRiskLevelToNull() {
        assertMappedEngineRiskLevelIsNull(FraudEngineStatus.DEGRADED, RiskLevel.MEDIUM);
    }

    @Test
    void mapsAvailableEngineRiskLevel() {
        assertThat(mapper.map(resultWithNormalizedEngine(
                AggregationTestSupport.normalized(
                        "rules.primary",
                        FraudEngineStatus.AVAILABLE,
                        0.8d,
                        RiskLevel.HIGH,
                        "HIGH_VELOCITY"
                )
        )).engines().getFirst().riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void mapsOperationalDiagnosticSignalRiskLevelToNull() {
        assertThat(mapper.map(resultWithSignal(signal(
                FraudEngineStatus.TIMEOUT,
                RiskLevel.HIGH,
                0.9d,
                FraudEngineSignalCategory.OPERATIONAL_SIGNAL
        ))).diagnosticSignals().getFirst().riskLevel()).isNull();
    }

    @Test
    void serializedTimeoutEngineIntelligenceDoesNotContainRiskLevelLow() throws Exception {
        String json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build().writeValueAsString(mapper.map(
                resultWithNormalizedEngine(AggregationTestSupport.normalized(
                        "rules.primary",
                        FraudEngineStatus.TIMEOUT,
                        0.1d,
                        RiskLevel.LOW,
                        "HIGH_VELOCITY"
                ))
        ));

        assertThat(json).doesNotContain("\"riskLevel\":\"LOW\"");
    }

    @Test
    void operationalDiagnosticSignalDoesNotExposeRiskLevel() {
        var summary = mapper.map(resultWithSignal(signal(
                FraudEngineStatus.TIMEOUT,
                RiskLevel.HIGH,
                0.9d,
                FraudEngineSignalCategory.OPERATIONAL_SIGNAL
        )));

        assertThat(summary.diagnosticSignals().getFirst().riskLevel()).isNull();
    }

    @Test
    void mapsOperationalSignalWithScoreToUnavailableBucket() {
        assertThat(mapper.map(resultWithSignal(signal(
                FraudEngineStatus.TIMEOUT,
                null,
                0.9d,
                FraudEngineSignalCategory.OPERATIONAL_SIGNAL
        ))).diagnosticSignals().getFirst().scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void operationalSignalDoesNotExposeAvailableScoreBucket() {
        var summary = mapper.map(resultWithSignal(signal(
                FraudEngineStatus.TIMEOUT,
                null,
                0.9d,
                FraudEngineSignalCategory.OPERATIONAL_SIGNAL
        )));

        assertThat(summary.diagnosticSignals().getFirst().scoreBucket())
                .isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void mapsDiagnosticSignalsWithoutRawEvidence() throws Exception {
        String json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(map(0.8d, RiskLevel.HIGH, 0.7d, RiskLevel.HIGH));

        assertThat(json).contains("diagnosticSignals").doesNotContain("evidence", "description", "contribution");
    }

    @Test
    void mapsWarningsAsCodeCountsOnly() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "UNSUPPORTED_SAFE_REASON"),
                AggregationTestSupport.available("ml.python.primary", 0.7d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        )));

        assertThat(summary.warnings()).containsExactly(new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.REASON_CODE_UNSUPPORTED_DROPPED,
                1
        ));
    }

    @Test
    void limitsReasonCodesBeforeCreatingPublicContract() {
        var summary = mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available(
                        "rules.primary",
                        0.95d,
                        RiskLevel.CRITICAL,
                        "DEVICE_NOVELTY",
                        "COUNTRY_MISMATCH",
                        "PROXY_OR_VPN",
                        "HIGH_VELOCITY",
                        "HIGH_AMOUNT_ACTIVITY",
                        "RAPID_PLN_20K_BURST"
                ),
                AggregationTestSupport.available("ml.python.primary", 0.7d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        )));

        assertThat(summary.engines().getFirst().reasonCodes()).hasSize(5);
        assertThat(summary.warnings()).contains(new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.REASON_CODE_LIMIT_APPLIED,
                1
        ));
    }

    @Test
    void mapsEveryInternalWarningCodeToPublicWarningCode() {
        for (FraudEngineAggregationWarningCode code : FraudEngineAggregationWarningCode.values()) {
            assertThat(mapper.map(resultWithWarnings(List.of(
                    new FraudEngineAggregationWarning("rules.primary", code)
            ))).warnings())
                    .extracting(EngineIntelligenceWarningSummary::code)
                    .containsExactly(EngineIntelligenceWarningCode.valueOf(code.name()));
        }
    }

    @Test
    void warningMappingDoesNotUseValueOf() throws Exception {
        assertThat(Files.readString(mapperSource()))
                .doesNotContain("EngineIntelligenceWarningCode.valueOf");
    }

    @Test
    void mapperDoesNotUseAgreementStatusValueOf() throws Exception {
        assertThat(Files.readString(mapperSource()))
                .doesNotContain("EngineIntelligenceAgreementStatus.valueOf");
    }

    @Test
    void mapperDoesNotUseRiskMismatchStatusValueOf() throws Exception {
        assertThat(Files.readString(mapperSource()))
                .doesNotContain("EngineIntelligenceRiskMismatchStatus.valueOf");
    }

    @Test
    void mapperDoesNotUseSignalCategoryValueOf() throws Exception {
        assertThat(Files.readString(mapperSource()))
                .doesNotContain("EngineIntelligenceSignalCategory.valueOf");
    }

    @Test
    void allInternalAgreementStatusesHavePublicCounterparts() {
        assertPublicCounterparts(FraudEngineAgreementStatus.values(), EngineIntelligenceAgreementStatus.class);
    }

    @Test
    void allInternalRiskMismatchStatusesHavePublicCounterparts() {
        assertPublicCounterparts(FraudEngineRiskMismatchStatus.values(), EngineIntelligenceRiskMismatchStatus.class);
    }

    @Test
    void allInternalSignalCategoriesHavePublicCounterparts() {
        assertPublicCounterparts(FraudEngineSignalCategory.values(), EngineIntelligenceSignalCategory.class);
    }

    @Test
    void allInternalWarningCodesHavePublicCounterparts() {
        assertPublicCounterparts(FraudEngineAggregationWarningCode.values(), EngineIntelligenceWarningCode.class);
    }

    @Test
    void doesNotExposeInternalAggregationObject() {
        assertThat(Arrays.stream(com.frauddetection.common.events.intelligence.EngineIntelligenceSummary.class
                .getRecordComponents()).map(RecordComponent::getType))
                .doesNotContain(FraudEngineAggregationResult.class);
    }

    private com.frauddetection.common.events.intelligence.EngineIntelligenceSummary map(
            double rulesScore,
            RiskLevel rulesRisk,
            double mlScore,
            RiskLevel mlRisk
    ) {
        return mapper.map(service.aggregate(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", rulesScore, rulesRisk, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", mlScore, mlRisk, "MODEL_HIGH_RISK")
        )));
    }

    private void assertScoreDeltaBucket(
            double rulesScore,
            double mlScore,
            EngineIntelligenceScoreDeltaBucket expected
    ) {
        assertThat(map(rulesScore, RiskLevel.HIGH, mlScore, RiskLevel.HIGH).comparison().scoreDeltaBucket())
                .isEqualTo(expected);
    }

    private FraudEngineAggregationResult resultWithNormalizedEngine(NormalizedFraudEngineResult normalized) {
        List<NormalizedFraudEngineResult> engines = requiredEnginesIncluding(normalized);
        return new FraudEngineAggregationResult(
                engines,
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                scoreDeltaFor(engines),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        );
    }

    private void assertMappedEngineRiskLevelIsNull(FraudEngineStatus status, RiskLevel riskLevel) {
        assertThat(mapper.map(resultWithNormalizedEngine(
                AggregationTestSupport.normalized("rules.primary", status, 0.9d, riskLevel, "HIGH_VELOCITY")
        )).engines().getFirst().riskLevel()).isNull();
    }

    private FraudEngineAggregationResult resultWithSignal(FraudEngineStrongestSignal signal) {
        return new FraudEngineAggregationResult(
                requiredEnginesFor(signal),
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE, null),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(signal),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        );
    }

    private FraudEngineAggregationResult resultWithWarnings(List<FraudEngineAggregationWarning> warnings) {
        return new FraudEngineAggregationResult(
                requiredAvailableRulesAndMl(),
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.AVAILABLE, 0.0d),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                warnings,
                AggregationTestSupport.GENERATED_AT
        );
    }

    private FraudEngineStrongestSignal signal(
            FraudEngineStatus status,
            RiskLevel riskLevel,
            Double score,
            FraudEngineSignalCategory signalCategory
    ) {
        return new FraudEngineStrongestSignal(
                "rules.primary",
                FraudEngineType.RULES,
                status,
                riskLevel,
                score,
                "HIGH_VELOCITY",
                null,
                signalCategory
        );
    }

    private <T extends Enum<T>> void assertPublicCounterparts(Enum<?>[] internalValues, Class<T> publicType) {
        for (Enum<?> internalValue : internalValues) {
            assertThatCode(() -> Enum.valueOf(publicType, internalValue.name())).doesNotThrowAnyException();
        }
    }

    private Path mapperSource() {
        Path moduleRelative = Path.of(
                "src/main/java/com/frauddetection/scoring/orchestration/aggregation/PublicEngineIntelligenceMapper.java"
        );
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("fraud-scoring-service").resolve(moduleRelative);
    }

    private List<NormalizedFraudEngineResult> requiredEnginesIncluding(NormalizedFraudEngineResult normalized) {
        if ("rules.primary".equals(normalized.engineId())) {
            return List.of(normalized, availableMl());
        }
        if ("ml.python.primary".equals(normalized.engineId())) {
            return List.of(availableRules(), normalized);
        }
        return List.of(availableRules(), availableMl(), normalized);
    }

    private List<NormalizedFraudEngineResult> requiredEnginesFor(FraudEngineStrongestSignal signal) {
        NormalizedFraudEngineResult matchingEngine = AggregationTestSupport.normalized(
                signal.engineId(),
                signal.status(),
                signal.score(),
                signal.riskLevel(),
                signal.reasonCode()
        );
        return requiredEnginesIncluding(matchingEngine);
    }

    private List<NormalizedFraudEngineResult> requiredAvailableRulesAndMl() {
        return List.of(availableRules(), availableMl());
    }

    private FraudEngineScoreDelta scoreDeltaFor(List<NormalizedFraudEngineResult> engines) {
        NormalizedFraudEngineResult rules = normalizedEngine(engines, "rules.primary");
        NormalizedFraudEngineResult ml = normalizedEngine(engines, "ml.python.primary");
        if (publiclyAvailable(rules) && publiclyAvailable(ml)) {
            return new FraudEngineScoreDelta(
                    FraudEngineScoreDeltaStatus.AVAILABLE,
                    Math.abs(rules.score() - ml.score())
            );
        }
        return new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE, null);
    }

    private NormalizedFraudEngineResult normalizedEngine(List<NormalizedFraudEngineResult> engines, String engineId) {
        return engines.stream()
                .filter(engine -> engineId.equals(engine.engineId()))
                .findFirst()
                .orElse(null);
    }

    private boolean publiclyAvailable(NormalizedFraudEngineResult engine) {
        return engine != null
                && engine.status() == FraudEngineStatus.AVAILABLE
                && engine.score() != null
                && engine.riskLevel() != null;
    }

    private NormalizedFraudEngineResult availableRules() {
        return AggregationTestSupport.normalized(
                "rules.primary",
                FraudEngineStatus.AVAILABLE,
                0.8d,
                RiskLevel.HIGH,
                "HIGH_VELOCITY"
        );
    }

    private NormalizedFraudEngineResult availableMl() {
        return AggregationTestSupport.normalized(
                "ml.python.primary",
                FraudEngineStatus.AVAILABLE,
                0.8d,
                RiskLevel.HIGH,
                "MODEL_HIGH_RISK"
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
}
