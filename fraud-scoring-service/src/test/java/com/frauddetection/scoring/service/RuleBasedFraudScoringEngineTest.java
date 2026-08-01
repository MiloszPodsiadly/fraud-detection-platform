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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
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
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 7)
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
    void shouldUseCanonicalReasonCodeTaxonomyInsteadOfRawReasonCodeStrings() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java"));

        assertThat(source).doesNotContain("reasonCodes.add(\"");
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

    private List<String> caseIds(JsonNode matrix) {
        return StreamSupport.stream(matrix.get("cases").spliterator(), false)
                .map(item -> item.get("caseId").textValue())
                .toList();
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
