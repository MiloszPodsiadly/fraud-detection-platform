package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedSignalEngineFeatureStatusTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private final RuleBasedFraudScoringEngine productionEngine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    private final RuleBasedSignalEngine engine =
            new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), productionEngine);

    @Test
    void presentTypedFeatureProducesBoundedMappedProductionSignal() {
        FraudSignalEvaluation result = engine.evaluate(context(event(true, false, false, 1, 0.1d, BigDecimal.TEN,
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
    }

    @Test
    void missingFeaturesSkipPreflightWithoutInventingSafeEvidence() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                Map.of())));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void invalidSnapshotTypeForTypedEventFieldDegradesWithoutTopLevelFallback() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, "5"));

        FraudSignalEvaluation result = engine.evaluate(context(event));

        assertDegradedInvalid(result);
        assertThat(flatten(result)).doesNotContain("5");
    }

    @Test
    void invalidSnapshotAmountTypeDegradesWithoutTopLevelMoneyFallback() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "20000.00"
                ))));

        assertDegradedInvalid(result);
        assertThat(flatten(result)).doesNotContain("20000.00");
    }

    @Test
    void negativeCanonicalCountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, -1))));

        assertDegradedInvalid(result);
    }

    @Test
    void negativeCanonicalAmountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("-0.01")
                ))));

        assertDegradedInvalid(result);
    }

    @Test
    void canonicalCountConflictingWithTopLevelCountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 4))));

        assertDegradedInvalid(result);
    }

    @Test
    void topLevelRecentAmountCannotContradictCanonicalRulesV2Amount() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("100.00"),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("200.00")
                ))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void canonicalAmountConsistencyIsSemanticNotScaleSensitive() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.0")
                ))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void kafkaSerdeWrongCanonicalWireTypeRemainsDetectableAndDegrades() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        TransactionEnrichedEvent source = event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, BigDecimal.TEN
                ));
        ObjectNode root = mapper.valueToTree(source);
        ((ObjectNode) root.path("featureSnapshot")).put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, "5");
        TransactionEnrichedEvent decoded = mapper.treeToValue(root, TransactionEnrichedEvent.class);

        FraudSignalEvaluation result = engine.evaluate(context(decoded));

        assertDegradedInvalid(result);
        assertThat(flatten(result)).doesNotContain("5");
    }

    @Test
    void countWithValidRulesWindowProducesHighVelocity() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
                ))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_VELOCITY.wireValue());
    }

    @Test
    void countWithInvalidRulesWindowDegradesWithoutRawWindowLeakage() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D"
                ))));

        assertDegradedInvalid(result);
        assertThat(flatten(result)).doesNotContain("P1D").doesNotContain("5");
    }

    @Test
    void removedTopLevelCountWindowCannotInfluenceRulesV2() {
        TransactionEnrichedEvent source = event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of());
        FraudSignalEvaluation result = engine.evaluate(context(source));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(flatten(result)).doesNotContain("P1D").doesNotContain("5");
    }

    @Test
    void countWithWrongWindowTypeDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, 60
                ))));

        assertDegradedInvalid(result);
    }

    @Test
    void removedTopLevelAmountWindowCannotInfluenceRulesV2() {
        TransactionEnrichedEvent source = event(false, false, false, 1, 1.0d, new BigDecimal("6000.00"),
                Map.of());
        FraudSignalEvaluation result = engine.evaluate(context(source));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
        assertThat(flatten(result)).doesNotContain("P1D").doesNotContain("6000.00");
    }

    @Test
    void wrongAccessorFailsFastAsAdapterBug() {
        assertThatThrownBy(() -> RuleBasedSignalEngine.degradedResultFor(
                FeatureSnapshotValueStatus.WRONG_ACCESSOR
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter feature accessor mismatch")
                .hasMessageNotContaining("deviceNovelty");
    }

    @Test
    void notAllowedFailsFastAsAdapterBugWithoutRawRejectedKey() {
        assertThatThrownBy(() -> RuleBasedSignalEngine.degradedResultFor(
                FeatureSnapshotValueStatus.NOT_ALLOWED
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter feature access policy violation")
                .hasMessageNotContaining("rawPayload")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret");
    }

    @Test
    void isolatedAdapterDoesNotAssignPublicationMetadata() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                Map.of())));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.statusReason()).isNull();
    }

    private ScoringContext context(TransactionEnrichedEvent event) {
        return new ScoringContext(
                event,
                event.featureSnapshot(),
                ScoringMode.RULE_BASED,
                "corr-rule-adapter-test",
                RECEIVED_AT
        );
    }

    private TransactionEnrichedEvent event(
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpn,
            int recentTransactionCount,
            double velocityPerMinute,
            BigDecimal amount,
            Map<String, Object> featureSnapshot
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
                new Money(amount, "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                canonicalSnapshot(
                        deviceNovelty,
                        countryMismatch,
                        proxyOrVpn,
                        recentTransactionCount,
                        amount,
                        featureSnapshot
                )
        );
    }

    private Map<String, Object> canonicalSnapshot(
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpn,
            int recentTransactionCount,
            BigDecimal amount,
            Map<String, Object> overrides
    ) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, recentTransactionCount);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, (double) recentTransactionCount);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, amount);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, amount);
        snapshot.put(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 1);
        snapshot.put(FraudFeatureContract.DEVICE_NOVELTY, deviceNovelty);
        snapshot.put(FraudFeatureContract.COUNTRY_MISMATCH, countryMismatch);
        snapshot.put(FraudFeatureContract.PROXY_OR_VPN_DETECTED, proxyOrVpn);
        snapshot.put(FraudFeatureContract.CURRENCY, "PLN");
        snapshot.putAll(overrides);
        return Map.copyOf(snapshot);
    }

    private String flatten(FraudSignalEvaluation result) {
        return result.reasonCodes() + " " + result.contributions() + " " + result.evidence() + " " + result.statusReason();
    }

    private void assertDegradedInvalid(FraudSignalEvaluation result) {
        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineConfidence.UNKNOWN);
        assertThat(result.statusReason()).isEqualTo(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
    }
}
