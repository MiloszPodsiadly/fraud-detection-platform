package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RuleBasedFraudScoringEngineTest {
    private static final Path RULES_V1_BASELINE_MATRIX = Path.of(
            "src/test/resources/fixtures/rules/rules_v1_baseline_matrix.json"
    );

    private final RuleBasedFraudScoringEngine engine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void frozenRulesV1BaselineMatrixMatchesCurrentPolicy() throws IOException {
        JsonNode matrix = mapper.readTree(RULES_V1_BASELINE_MATRIX.toFile());

        assertThat(matrix.get("source").get("baseSha").textValue())
                .isEqualTo("61cb3052c918d07c57905ca02c859db585f9474b");
        assertThat(caseIds(matrix)).contains(
                "normal_activity",
                "count_4_pt1m_rate_4",
                "count_5_pt1m_rate_5",
                "rapid_count_2_pln_20000",
                "combined_near_high_threshold"
        );

        for (JsonNode baselineCase : matrix.get("cases")) {
            FraudScoreResult result = engine.score(FraudScoringRequest.from(eventFrom(baselineCase)));
            JsonNode expected = baselineCase.get("expected");

            assertThat(result.fraudScore())
                    .as(baselineCase.get("caseId").textValue())
                    .isCloseTo(expected.get("score").doubleValue(), within(0.000001d));
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.valueOf(expected.get("riskLevel").textValue()));
            assertThat(result.alertRecommended()).isEqualTo(expected.get("alertRecommended").booleanValue());
            assertThat(result.reasonCodes()).containsExactlyElementsOf(textValues(expected.get("reasonCodes")));
            assertThat(result.modelName()).isEqualTo("rule-based-engine");
            assertThat(result.modelVersion()).isEqualTo("v1");
        }
    }

    @Test
    void frozenRulesV1BaselineMatrixHasExplicitContributionWeightAndSourceOracle() throws IOException {
        JsonNode matrix = mapper.readTree(RULES_V1_BASELINE_MATRIX.toFile());

        for (JsonNode baselineCase : matrix.get("cases")) {
            String caseId = baselineCase.get("caseId").textValue();
            FraudScoreResult result = engine.score(FraudScoringRequest.from(eventFrom(baselineCase)));

            assertThat(result.scoreDetails())
                    .as(caseId)
                    .containsAllEntriesOf(expectedScoreDetailOracle().get(caseId));
        }
    }

    @Test
    void sameCanonicalHighVelocityFactsScoreTheSameWithOrWithoutLegacyFlag() {
        TransactionEnrichedEvent withoutLegacyFlag = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent withLegacyFlag = withFlags(
                withoutLegacyFlag,
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY)
        );

        assertSameCoreResult(withLegacyFlag, withoutLegacyFlag);
        assertThat(score(withoutLegacyFlag).reasonCodes()).containsExactly(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(score(withoutLegacyFlag).scoreDetails())
                .containsEntry("highVelocityRulesV1Weight", RulesScoringPolicyV1.HIGH_VELOCITY_WEIGHT)
                .doesNotContainKeys("high_velocityWeight", "recentTransactionSpikeBoost", "transactionVelocityBoost");
    }

    @Test
    void sameCanonicalRapidTransferFactsScoreTheSameWithOrWithoutLegacyFlag() {
        TransactionEnrichedEvent withoutLegacyFlag = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent withLegacyFlag = withFlags(
                withoutLegacyFlag,
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY, FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST)
        );

        assertSameCoreResult(withLegacyFlag, withoutLegacyFlag);
        assertThat(score(withoutLegacyFlag).reasonCodes()).containsExactly(
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
                ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue()
        );
        assertThat(score(withoutLegacyFlag).scoreDetails())
                .containsEntry("rapidPln20kBurstRulesV1Weight", RulesScoringPolicyV1.RAPID_PLN_20K_BURST_WEIGHT)
                .doesNotContainKeys("rapid_pln_20k_burstWeight", "recentAmountAccumulationBoost", "rapidTransferFraudCaseBoost");
    }

    @Test
    void shouldProduceCriticalRiskForStrongFraudSignals() {
        var event = new TransactionEnrichedEvent(
                java.util.UUID.randomUUID().toString(),
                "txn-9001",
                "corr-9001",
                "cust-9001",
                "acct-9001",
                Instant.now(),
                Instant.now(),
                new Money(new BigDecimal("1499.99"), "USD"),
                TransactionFixtures.enrichedTransaction().build().merchantInfo(),
                TransactionFixtures.enrichedTransaction().build().deviceInfo(),
                TransactionFixtures.enrichedTransaction().build().locationInfo(),
                TransactionFixtures.enrichedTransaction().build().customerContext(),
                7,
                "PT1M",
                new Money(new BigDecimal("6400.00"), "USD"),
                "PT1M",
                7.0d,
                7,
                true,
                true,
                true,
                List.of(
                        FraudFeatureContract.FLAG_DEVICE_NOVELTY,
                        FraudFeatureContract.FLAG_COUNTRY_MISMATCH,
                        FraudFeatureContract.FLAG_PROXY_OR_VPN,
                        FraudFeatureContract.FLAG_HIGH_VELOCITY,
                        FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY
                ),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 7,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
                )
        );

        var result = engine.score(FraudScoringRequest.from(event));

        assertThat(result.fraudScore()).isGreaterThanOrEqualTo(0.90d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.reasonCodes()).contains(
                ReasonCode.DEVICE_NOVELTY.wireValue(),
                ReasonCode.COUNTRY_MISMATCH.wireValue(),
                ReasonCode.HIGH_VELOCITY.wireValue(),
                ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue()
        );
        assertThat(result.reasonCodes()).allSatisfy(reasonCode ->
                assertThat(ReasonCode.known(reasonCode)).isPresent()
        );
        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.UNKNOWN.wireValue());
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.modelVersion()).isEqualTo("v1");
        assertThat(result.inferenceTimestamp()).isNotNull();
        assertThat(result.featureSnapshot()).isEqualTo(event.featureSnapshot());
        assertThat(result.explanationMetadata()).containsEntry("explanationType", "WEIGHTED_REASON_CODES");
        assertThat(result.scoreDetails()).containsKey("explanationMetadata");
        assertThat(result.alertRecommended()).isTrue();
    }

    @Test
    void shouldKeepLowRiskForBaselineTraffic() {
        var result = score(event(
                1,
                1.0d,
                new BigDecimal("45.50"),
                new BigDecimal("45.50"),
                List.of(),
                false,
                false,
                false
        ));

        assertThat(result.fraudScore()).isLessThan(0.45d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.alertRecommended()).isFalse();
    }

    @Test
    void shouldKeepSingleHighAmountTransactionLowWhileKeepingDiagnosticReason() {
        var result = score(event(
                1,
                1.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                List.of(),
                false,
                false,
                false
        ));

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
        assertThat(result.alertRecommended()).isFalse();
    }

    @Test
    void absentCanonicalRapidFactStillAllowsLegacyCandidateFallback() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent legacyCandidateOnly = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
        ));

        FraudScoreResult result = score(legacyCandidateOnly);

        assertThat(result.reasonCodes()).contains(
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue()
        );
        assertThat(result.fraudScore()).isCloseTo(0.35d, within(0.000001d));
    }

    @Test
    void malformedPresentCanonicalRapidFactFailsClosedWithoutLegacyCandidateFallback() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                false,
                false,
                false
        );
        TransactionEnrichedEvent malformedCanonical = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "20000.00",
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
        ));

        assertRulesInputInvalid(malformedCanonical);
    }

    @Test
    void malformedPresentCanonicalCountFailsClosedWithoutHighVelocityFlagFallback() {
        TransactionEnrichedEvent source = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent malformedCanonical = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5.5d
        ));

        assertRulesInputInvalid(malformedCanonical);
    }

    @Test
    void canonicalCountStringWithValidTopLevelCountIsRejectedBeforeLowRiskScoring() {
        TransactionEnrichedEvent source = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent malformedCanonical = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "5"
        ));

        assertRulesInputInvalid(malformedCanonical);
    }

    @Test
    void canonicalRapidCountBelowThresholdWinsOverLegacyCandidate() {
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(
                topLevelOnly(List.of(), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_COUNT, 1,
                        FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M",
                        FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
                )
        );

        FraudScoreResult result = score(canonicalFalse);
        RulesV1SignalResolution resolution = signal(canonicalFalse, ReasonCode.RAPID_PLN_20K_BURST);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.FALSE);
        assertThat(resolution.contributes()).isFalse();
    }

    @Test
    void canonicalAmountEquivalentToTopLevelDifferentScaleIsAccepted() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent sameSemanticAmount = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.0")
        ));

        FraudScoreResult result = score(sameSemanticAmount);

        assertThat(result.reasonCodes()).contains(
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue()
        );
    }

    @Test
    void canonicalPlnAmountDoesNotConflictWithNonPlnTopLevelRecentAmount() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("6000.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent nonPlnTopLevel = new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                new Money(new BigDecimal("1000.00"), "USD"),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.recentTransactionCount(),
                source.recentTransactionCountWindow(),
                new Money(new BigDecimal("1000.00"), "USD"),
                source.recentAmountSumWindow(),
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                source.featureSnapshot()
        );

        FraudScoreResult result = score(nonPlnTopLevel);

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void canonicalHighAmountPredicateFalseWinsOverLegacyFlag() {
        TransactionEnrichedEvent canonicalFalse = event(
                1,
                1.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                false,
                false,
                false
        );

        FraudScoreResult result = score(canonicalFalse);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(result.scoreDetails()).doesNotContainKey("recentAmountActivityRulesV1Weight");
    }

    @Test
    void canonicalHighAmountCountBelowThresholdWithMissingAmountWinsOverLegacyFlag() {
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
                )
        );

        FraudScoreResult result = score(canonicalFalse);
        RulesV1SignalResolution resolution = signal(canonicalFalse, ReasonCode.HIGH_AMOUNT_ACTIVITY);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.FALSE);
        assertThat(resolution.contributes()).isFalse();
    }

    @Test
    void canonicalHighAmountAmountBelowThresholdWithMissingCountWinsOverLegacyFlag() {
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00"),
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"
                )
        );

        FraudScoreResult result = score(canonicalFalse);
        RulesV1SignalResolution resolution = signal(canonicalFalse, ReasonCode.HIGH_AMOUNT_ACTIVITY);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.FALSE);
        assertThat(resolution.contributes()).isFalse();
    }

    @Test
    void canonicalHighAmountCountSatisfiedWithMissingAmountAllowsLegacyFallbackAsAbsent() {
        TransactionEnrichedEvent canonicalAbsent = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
                )
        );

        FraudScoreResult result = score(canonicalAbsent);
        RulesV1SignalResolution resolution = signal(canonicalAbsent, ReasonCode.HIGH_AMOUNT_ACTIVITY);

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.ABSENT);
        assertThat(resolution.contributes()).isTrue();
    }

    @Test
    void canonicalHighAmountAmountSatisfiedWithMissingCountAllowsLegacyFallbackAsAbsent() {
        TransactionEnrichedEvent canonicalAbsent = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("6000.00"),
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"
                )
        );

        FraudScoreResult result = score(canonicalAbsent);
        RulesV1SignalResolution resolution = signal(canonicalAbsent, ReasonCode.HIGH_AMOUNT_ACTIVITY);

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.ABSENT);
        assertThat(resolution.contributes()).isTrue();
    }

    @Test
    void canonicalHighAmountPredicateTrueDoesNotRequireLegacyFlag() {
        FraudScoreResult result = score(event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                List.of(),
                false,
                false,
                false
        ));

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void canonicalRapidPairFalseWinsOverLegacyFlagAndCandidate() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                false,
                false,
                false
        );
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RAPID_TRANSFER_COUNT, 1,
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
        ));

        FraudScoreResult result = score(canonicalFalse);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
    }

    @Test
    void canonicalRapidAmountBelowThresholdWithMissingCountWinsOverLegacyFlag() {
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("100.00"),
                        FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M"
                )
        );

        FraudScoreResult result = score(canonicalFalse);
        RulesV1SignalResolution resolution = signal(canonicalFalse, ReasonCode.RAPID_PLN_20K_BURST);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.FALSE);
        assertThat(resolution.contributes()).isFalse();
    }

    @Test
    void malformedCanonicalCountWithLegacyHighAmountFlagIsInvalid() {
        TransactionEnrichedEvent invalid = withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY), null, null, null, null),
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, "2")
        );
        RulesV1SignalResolution resolution = signal(invalid, ReasonCode.HIGH_AMOUNT_ACTIVITY);

        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.INVALID);
        assertThat(resolution.contributes()).isFalse();
        assertRulesInputInvalid(invalid);
    }

    @Test
    void malformedCanonicalRapidAmountWithLegacyCandidateIsInvalid() {
        TransactionEnrichedEvent invalid = withFeatureSnapshot(
                topLevelOnly(List.of(), null, null, null, null),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, "20000.00",
                        FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
                )
        );
        RulesV1SignalResolution resolution = signal(invalid, ReasonCode.RAPID_PLN_20K_BURST);

        assertThat(resolution.predicateResolution()).isEqualTo(PredicateResolution.INVALID);
        assertThat(resolution.contributes()).isFalse();
        assertRulesInputInvalid(invalid);
    }

    @Test
    void recentCanonicalFactsFalseWinOverLegacyRapidCandidate() {
        TransactionEnrichedEvent source = event(
                1,
                1.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent canonicalFalse = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
        ));

        FraudScoreResult result = score(canonicalFalse);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
    }

    @Test
    void topLevelPlnRecentAmountCanBeUsedAsCompatibilityFallback() {
        TransactionEnrichedEvent source = withoutFactualInputs(event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                List.of(),
                false,
                false,
                false
        ));
        TransactionEnrichedEvent topLevelPln = withTopLevelRecentAmount(source, 2, new BigDecimal("5000.00"), "PLN");

        FraudScoreResult result = score(topLevelPln);

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void topLevelNonPlnRecentAmountDoesNotTriggerPlnAmountThreshold() {
        TransactionEnrichedEvent source = withoutFactualInputs(event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                List.of(),
                false,
                false,
                false
        ));
        TransactionEnrichedEvent topLevelUsd = withTopLevelRecentAmount(source, 2, new BigDecimal("5000.00"), "USD");

        FraudScoreResult result = score(topLevelUsd);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void topLevelNonPlnRecentAmountDoesNotTriggerRapidPlnBurst() {
        TransactionEnrichedEvent source = withoutFactualInputs(event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("20000.00"),
                List.of(),
                false,
                false,
                false
        ));
        TransactionEnrichedEvent topLevelEur = withTopLevelRecentAmount(source, 2, new BigDecimal("20000.00"), "EUR");

        FraudScoreResult result = score(topLevelEur);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
    }

    @Test
    void legacyHighVelocityFlagOnlyKeepsHistoricalFlagContribution() {
        FraudScoreResult result = score(topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                null,
                null,
                null,
                null
        ));

        assertThat(result.fraudScore()).isCloseTo(0.25d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(result.scoreDetails()).containsEntry("highVelocityRulesV1Weight", 0.20d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullFeatureFlagsBehaveAsEmptyImmutableListWithoutInventedSignals() {
        FraudScoreResult result = score(topLevelOnly(null, null, null, null, null));
        List<String> featureFlags = (List<String>) result.scoreDetails().get("featureFlags");

        assertThat(result.reasonCodes()).isEmpty();
        assertThat(featureFlags).isEmpty();
        assertThatThrownBy(() -> featureFlags.add(FraudFeatureContract.FLAG_HIGH_VELOCITY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void topLevelCountOnlyKeepsHistoricalCountContribution() {
        FraudScoreResult result = score(topLevelOnly(List.of(), 5, null, null, null));

        assertThat(result.fraudScore()).isCloseTo(0.15d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(result.scoreDetails()).containsEntry("highVelocityRulesV1Weight", 0.10d);
    }

    @Test
    void topLevelCountWithP1dWindowFailsClosedWithoutLegacyFlagFallback() {
        TransactionEnrichedEvent invalidWindow = topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                5,
                null,
                null,
                null,
                "P1D",
                null
        );

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void topLevelCountWithPt5mWindowFailsClosed() {
        TransactionEnrichedEvent invalidWindow = topLevelOnly(List.of(), 5, null, null, null, "PT5M", null);

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void topLevelCountWithMissingWindowFailsClosed() {
        TransactionEnrichedEvent missingWindow = topLevelOnly(List.of(), 5, null, null, null, null, null);

        assertRulesInputInvalid(missingWindow);
    }

    @Test
    void topLevelRateOnlyKeepsHistoricalVelocityContribution() {
        FraudScoreResult result = score(topLevelOnly(List.of(), null, 5.0d, null, null));

        assertThat(result.fraudScore()).isCloseTo(0.17d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(result.scoreDetails()).containsEntry("highVelocityRulesV1Weight", 0.12d);
    }

    @Test
    void completeHistoricalHighVelocityRepresentationKeepsHistoricalSum() {
        FraudScoreResult result = score(topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                5,
                5.0d,
                null,
                null
        ));

        assertThat(result.fraudScore()).isCloseTo(0.47d, within(0.000001d));
        assertThat(result.scoreDetails()).containsEntry("highVelocityRulesV1Weight", 0.42d);
    }

    @Test
    void legacyHighAmountFlagOnlyKeepsHistoricalFlagContribution() {
        FraudScoreResult result = score(topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                null,
                null,
                null,
                null
        ));

        assertThat(result.fraudScore()).isCloseTo(0.19d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(result.scoreDetails()).containsEntry("recentAmountActivityRulesV1Weight", 0.14d);
    }

    @Test
    void topLevelAmountOnlyKeepsHistoricalAmountContribution() {
        FraudScoreResult result = score(topLevelOnly(List.of(), null, null, new BigDecimal("5000.00"), "PLN"));

        assertThat(result.fraudScore()).isCloseTo(0.15d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(result.scoreDetails()).containsEntry("recentAmountActivityRulesV1Weight", 0.10d);
    }

    @Test
    void topLevelPlnAmountWithP1dWindowFailsClosedWithoutLegacyFlagFallback() {
        TransactionEnrichedEvent invalidWindow = topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                null,
                null,
                new BigDecimal("6000.00"),
                "PLN",
                null,
                "P1D"
        );

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void topLevelPlnAmountWithMissingWindowFailsClosed() {
        TransactionEnrichedEvent missingWindow = topLevelOnly(
                List.of(),
                null,
                null,
                new BigDecimal("6000.00"),
                "PLN",
                null,
                null
        );

        assertRulesInputInvalid(missingWindow);
    }

    @Test
    void unsupportedTopLevelRecentAmountCurrencyFailsClosedBeforeRulesThresholdEvaluation() {
        TransactionEnrichedEvent unsupportedCurrency = topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                null,
                null,
                new BigDecimal("6000.00"),
                "XXX"
        );

        assertRulesInputInvalid(unsupportedCurrency);
    }

    @Test
    void nullTopLevelRecentAmountCurrencyFailsClosedBeforeRulesThresholdEvaluation() {
        TransactionEnrichedEvent nullCurrency = topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                null,
                null,
                new BigDecimal("6000.00"),
                null
        );

        assertRulesInputInvalid(nullCurrency);
    }

    @Test
    void completeHistoricalHighAmountRepresentationKeepsHistoricalSum() {
        FraudScoreResult result = score(topLevelOnly(
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                null,
                null,
                new BigDecimal("5000.00"),
                "PLN"
        ));

        assertThat(result.fraudScore()).isCloseTo(0.29d, within(0.000001d));
        assertThat(result.scoreDetails()).containsEntry("recentAmountActivityRulesV1Weight", 0.24d);
    }

    @Test
    void legacyRapidCandidateOnlyKeepsHistoricalCandidateContribution() {
        FraudScoreResult result = score(withFeatureSnapshot(
                topLevelOnly(List.of(), null, null, null, null),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true)
        ));

        assertThat(result.fraudScore()).isCloseTo(0.25d, within(0.000001d));
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
        assertThat(result.scoreDetails()).containsEntry("rapidPln20kBurstRulesV1Weight", 0.20d);
    }

    @Test
    void legacyRapidFlagOnlyKeepsHistoricalFlagContribution() {
        FraudScoreResult result = score(topLevelOnly(
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                null,
                null,
                null,
                null
        ));

        assertThat(result.fraudScore()).isCloseTo(0.50d, within(0.000001d));
        assertThat(result.scoreDetails()).containsEntry("rapidPln20kBurstRulesV1Weight", 0.45d);
    }

    @Test
    void legacyRapidFlagAndCandidateKeepHistoricalSum() {
        FraudScoreResult result = score(withFeatureSnapshot(
                topLevelOnly(List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST), null, null, null, null),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true)
        ));

        assertThat(result.fraudScore()).isCloseTo(0.70d, within(0.000001d));
        assertThat(result.scoreDetails()).containsEntry("rapidPln20kBurstRulesV1Weight", 0.65d);
    }

    @Test
    void topLevelPlnAmountWithInvalidWindowFailsClosedWithoutRapidCandidateFallback() {
        TransactionEnrichedEvent invalidWindow = withFeatureSnapshot(
                topLevelOnly(
                        List.of(),
                        null,
                        null,
                        new BigDecimal("6000.00"),
                        "PLN",
                        null,
                        "P1D"
                ),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true)
        );

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void canonicalCountWithInvalidWindowFailsClosedBeforeHighVelocityScoring() {
        TransactionEnrichedEvent source = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent invalidWindow = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D"
        ));

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void recentAmountFactsWithInvalidWindowFailClosed() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("1000.00"),
                new BigDecimal("6000.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent invalidWindow = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("6000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT5M"
        ));

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void rapidTransferFactsWithValidWindowKeepRapidSignal() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent rapid = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RAPID_TRANSFER_COUNT, 2,
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M"
        ));

        FraudScoreResult result = score(rapid);

        assertThat(result.reasonCodes()).contains(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
    }

    @Test
    void rapidTransferFactsWithInvalidWindowFailClosed() {
        TransactionEnrichedEvent source = event(
                2,
                2.0d,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                false,
                false,
                false
        );
        TransactionEnrichedEvent invalidWindow = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RAPID_TRANSFER_COUNT, 2,
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RAPID_TRANSFER_WINDOW, "P7D",
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
        ));

        assertRulesInputInvalid(invalidWindow);
    }

    @Test
    void presentCanonicalValueWithoutPairedWindowFailsClosed() {
        TransactionEnrichedEvent source = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent missingWindow = withFeatureSnapshot(source, Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5
        ));

        assertRulesInputInvalid(missingWindow);
    }

    @Test
    void historicalEventWithCanonicalFactsAbsentStillUsesLegacyFallback() {
        TransactionEnrichedEvent source = event(
                5,
                5.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        TransactionEnrichedEvent historical = withoutFactualInputs(withFeatureSnapshot(source, Map.of()));

        FraudScoreResult result = score(historical);

        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_VELOCITY.wireValue());
    }

    @Test
    void legacyRapidFallbackWorksOnlyWhenFactualInputsAreAbsent() {
        TransactionEnrichedEvent source = event(
                1,
                1.0d,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                false,
                false,
                false
        );
        TransactionEnrichedEvent historical = withoutFactualInputs(withFeatureSnapshot(source, Map.of()));

        FraudScoreResult result = score(historical);

        assertThat(result.reasonCodes()).contains(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
    }

    @Test
    void shouldUseCanonicalReasonCodeTaxonomyInsteadOfRawReasonCodeStrings() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java"));

        assertThat(source).doesNotContain("reasonCodes.add(\"");
    }

    @Test
    void validatedInputCannotBeDetachedToAuthorizeAnotherEvent() {
        TransactionEnrichedEvent eventA = event(
                1,
                1.0d,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                List.of(),
                false,
                false,
                false
        );
        TransactionEnrichedEvent eventB = event(
                5,
                4.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );
        ValidatedRulesInput inputA = RulesFeatureInputValidator.requireValidInput(eventA);

        FraudScoreResult result = engine.scoreValidated(inputA);

        assertThat(result.fraudScore()).isEqualTo(0.05d);
        assertThat(result.reasonCodes()).isEmpty();
        assertRulesInputInvalid(eventB);
    }

    @Test
    void mutationAfterValidationDoesNotAffectValidatedScoringInput() {
        Map<String, Object> mutableSnapshot = new HashMap<>();
        mutableSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1);
        mutableSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M");
        TransactionEnrichedEvent event = withFeatureSnapshot(
                topLevelOnly(List.of(), null, null, null, null),
                mutableSnapshot
        );
        ValidatedRulesInput input = RulesFeatureInputValidator.requireValidInput(event);

        mutableSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5);
        mutableSnapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d);

        FraudScoreResult result = engine.scoreValidated(input);

        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(result.featureSnapshot()).containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1);
        assertThat(result.featureSnapshot()).doesNotContainKey(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE);
    }

    @Test
    void validatedRulesInputHasNoPublicArbitraryConstructor() {
        assertThat(ValidatedRulesInput.class.getConstructors()).isEmpty();
        assertThat(ValidatedRulesInput.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());

        TransactionEnrichedEvent invalid = event(
                5,
                4.0d,
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                false,
                false,
                false
        );

        assertThatThrownBy(() -> RulesFeatureInputValidator.requireValidInput(invalid))
                .isInstanceOf(RulesFeatureInputValidationException.class);
    }

    @Test
    void scoringEngineDoesNotExposeDetachedValidatedRequestSignature() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java"));

        assertThat(source).doesNotContain("scoreValidated(FraudScoringRequest request, RulesInputValidationResult validation)");
        assertThat(source).contains("scoreValidated(ValidatedRulesInput input)");
    }

    private void assertSameCoreResult(TransactionEnrichedEvent left, TransactionEnrichedEvent right) {
        FraudScoreResult leftResult = score(left);
        FraudScoreResult rightResult = score(right);

        assertThat(leftResult.fraudScore()).isEqualTo(rightResult.fraudScore());
        assertThat(leftResult.riskLevel()).isEqualTo(rightResult.riskLevel());
        assertThat(leftResult.alertRecommended()).isEqualTo(rightResult.alertRecommended());
        assertThat(leftResult.reasonCodes()).containsExactlyElementsOf(rightResult.reasonCodes());
    }

    private FraudScoreResult score(TransactionEnrichedEvent event) {
        return engine.score(FraudScoringRequest.from(event));
    }

    private RulesV1SignalResolution signal(TransactionEnrichedEvent event, ReasonCode reasonCode) {
        return RulesV1CompatibilityResolver.resolve(event).stream()
                .filter(signal -> signal.reasonCode().equals(reasonCode.wireValue()))
                .findFirst()
                .orElseThrow();
    }

    private void assertRulesInputInvalid(TransactionEnrichedEvent event) {
        assertThatThrownBy(() -> score(event))
                .isInstanceOf(RulesFeatureInputValidationException.class)
                .hasMessage("RULES_FEATURE_INPUT_INVALID")
                .hasMessageNotContaining("5")
                .hasMessageNotContaining("20000.00")
                .hasMessageNotContaining("P1D")
                .hasMessageNotContaining("PT5M")
                .hasMessageNotContaining("XXX")
                .hasMessageNotContaining("JPY")
                .hasMessageNotContaining("featureSnapshot");
    }

    private TransactionEnrichedEvent eventFrom(JsonNode baselineCase) {
        JsonNode facts = baselineCase.get("facts");
        return event(
                facts.get("count").intValue(),
                facts.get("rate").doubleValue(),
                new BigDecimal(facts.get("currentAmountPln").textValue()),
                new BigDecimal(facts.get("recentAmountSumPln").textValue()),
                textValues(baselineCase.get("featureFlags")),
                booleanFact(facts, "deviceNovelty"),
                booleanFact(facts, "countryMismatch"),
                booleanFact(facts, "proxyOrVpn")
        );
    }

    private TransactionEnrichedEvent event(
            int recentTransactionCount,
            double transactionVelocityPerMinute,
            BigDecimal currentAmountPln,
            BigDecimal recentAmountSumPln,
            List<String> featureFlags,
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpn
    ) {
        boolean rapidTransferCandidate = recentTransactionCount >= 2
                && recentAmountSumPln.compareTo(new BigDecimal("20000.00")) >= 0;
        Map<String, Object> featureSnapshot = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, recentTransactionCount,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, recentAmountSumPln,
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_COUNT, recentTransactionCount,
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, recentAmountSumPln,
                FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M",
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, transactionVelocityPerMinute,
                FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, rapidTransferCandidate,
                FraudFeatureContract.FEATURE_FLAGS, List.copyOf(featureFlags)
        );
        return new TransactionEnrichedEvent(
                java.util.UUID.randomUUID().toString(),
                "txn-rules-v1",
                "corr-rules-v1",
                "cust-rules-v1",
                "acct-rules-v1",
                Instant.now(),
                Instant.now(),
                new Money(currentAmountPln, "PLN"),
                TransactionFixtures.enrichedTransaction().build().merchantInfo(),
                TransactionFixtures.enrichedTransaction().build().deviceInfo(),
                TransactionFixtures.enrichedTransaction().build().locationInfo(),
                TransactionFixtures.enrichedTransaction().build().customerContext(),
                recentTransactionCount,
                "PT1M",
                new Money(recentAmountSumPln, "PLN"),
                "PT1M",
                transactionVelocityPerMinute,
                1,
                deviceNovelty,
                countryMismatch,
                proxyOrVpn,
                List.copyOf(featureFlags),
                featureSnapshot
        );
    }

    private TransactionEnrichedEvent withFlags(TransactionEnrichedEvent source, List<String> featureFlags) {
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.recentTransactionCount(),
                source.recentTransactionCountWindow(),
                source.recentAmountSum(),
                source.recentAmountSumWindow(),
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                List.copyOf(featureFlags),
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withFeatureSnapshot(TransactionEnrichedEvent source, Map<String, Object> featureSnapshot) {
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.recentTransactionCount(),
                source.recentTransactionCountWindow(),
                source.recentAmountSum(),
                source.recentAmountSumWindow(),
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                featureSnapshot
        );
    }

    private TransactionEnrichedEvent withoutFactualInputs(TransactionEnrichedEvent source) {
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                null,
                null,
                null,
                null,
                null,
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withTopLevelRecentAmount(
            TransactionEnrichedEvent source,
            Integer recentTransactionCount,
            BigDecimal amount,
            String currency
    ) {
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                recentTransactionCount,
                "PT1M",
                new Money(amount, currency),
                "PT1M",
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                Map.of()
        );
    }

    private TransactionEnrichedEvent topLevelOnly(
            List<String> featureFlags,
            Integer recentTransactionCount,
            Double transactionVelocityPerMinute,
            BigDecimal recentAmount,
            String recentAmountCurrency
    ) {
        return topLevelOnly(
                featureFlags,
                recentTransactionCount,
                transactionVelocityPerMinute,
                recentAmount,
                recentAmountCurrency,
                recentTransactionCount == null ? null : "PT1M",
                recentAmount == null ? null : "PT1M"
        );
    }

    private TransactionEnrichedEvent topLevelOnly(
            List<String> featureFlags,
            Integer recentTransactionCount,
            Double transactionVelocityPerMinute,
            BigDecimal recentAmount,
            String recentAmountCurrency,
            String recentTransactionCountWindow,
            String recentAmountWindow
    ) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                new Money(new BigDecimal("100.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                recentTransactionCount,
                recentTransactionCountWindow,
                recentAmount == null ? null : new Money(recentAmount, recentAmountCurrency),
                recentAmountWindow,
                transactionVelocityPerMinute,
                base.merchantFrequency7d(),
                false,
                false,
                false,
                featureFlags,
                Map.of()
        );
    }

    private List<String> caseIds(JsonNode matrix) {
        return StreamSupport.stream(matrix.get("cases").spliterator(), false)
                .map(item -> item.get("caseId").textValue())
                .toList();
    }

    private Map<String, Map<String, Object>> expectedScoreDetailOracle() {
        return Map.ofEntries(
                Map.entry("normal_activity", Map.of()),
                Map.entry("count_4_pt1m_rate_4", Map.of()),
                Map.entry("count_5_pt1m_rate_5", highVelocityOracle()),
                Map.entry("count_6_pt1m_rate_6", highVelocityOracle()),
                Map.entry("rapid_count_2_pln_19999_99", highAmountOracle()),
                Map.entry("rapid_count_2_pln_20000", merge(highAmountOracle(), rapidOracle())),
                Map.entry("rapid_count_5_high_pln_amount", merge(highVelocityOracle(), highAmountOracle(), rapidOracle())),
                Map.entry("high_recent_amount_without_rapid_burst", highAmountOracle()),
                Map.entry("legacy_high_velocity_flag_present", highVelocityOracle()),
                Map.entry("legacy_high_velocity_flag_absent", highVelocityOracle()),
                Map.entry("device_novelty", Map.of(
                        "device_noveltyWeight", 0.18d,
                        "deviceNoveltyBoost", 0.10d
                )),
                Map.entry("country_mismatch", Map.of(
                        "country_mismatchWeight", 0.24d,
                        "countryMismatchBoost", 0.12d
                )),
                Map.entry("proxy_vpn", Map.of(
                        "proxy_or_vpnWeight", 0.16d,
                        "proxyOrVpnBoost", 0.10d
                )),
                Map.entry("combined_near_high_threshold", merge(highVelocityOracle(), Map.of(
                        "country_mismatchWeight", 0.24d,
                        "countryMismatchBoost", 0.12d
                ))),
                Map.entry("combined_critical_threshold", merge(highVelocityOracle(), Map.of(
                        "device_noveltyWeight", 0.18d,
                        "deviceNoveltyBoost", 0.10d,
                        "country_mismatchWeight", 0.24d,
                        "countryMismatchBoost", 0.12d,
                        "proxy_or_vpnWeight", 0.16d,
                        "proxyOrVpnBoost", 0.10d
                )))
        );
    }

    @SafeVarargs
    private final Map<String, Object> merge(Map<String, Object>... maps) {
        Map<String, Object> merged = new HashMap<>();
        for (Map<String, Object> map : maps) {
            merged.putAll(map);
        }
        return Map.copyOf(merged);
    }

    private Map<String, Object> highVelocityOracle() {
        return Map.of(
                "highVelocityRulesV1Weight", 0.42d,
                "highVelocityRulesV1Sources", List.of("CANONICAL", "TOP_LEVEL_COMPATIBILITY")
        );
    }

    private Map<String, Object> highAmountOracle() {
        return Map.of(
                "recentAmountActivityRulesV1Weight", 0.24d,
                "recentAmountActivityRulesV1Sources", List.of("CANONICAL")
        );
    }

    private Map<String, Object> rapidOracle() {
        return Map.of(
                "rapidPln20kBurstRulesV1Weight", 0.65d,
                "rapidPln20kBurstRulesV1Sources", List.of("CANONICAL")
        );
    }

    private List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.textValue()));
        return List.copyOf(values);
    }

    private boolean booleanFact(JsonNode facts, String fieldName) {
        JsonNode value = facts.get(fieldName);
        return value != null && value.booleanValue();
    }
}
