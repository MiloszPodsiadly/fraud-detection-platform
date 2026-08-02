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
import java.util.List;
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
                List.of(),
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
    }

    @Test
    void missingFeaturesSkipPreflightWithoutInventingSafeEvidence() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(),
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
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, "5"));

        FraudSignalEvaluation result = engine.evaluate(context(event));

        assertDegradedInvalid(result);
        assertThat(flatten(result)).doesNotContain("5");
    }

    @Test
    void invalidSnapshotAmountTypeDegradesWithoutTopLevelMoneyFallback() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
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
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, -1))));

        assertDegradedInvalid(result);
    }

    @Test
    void negativeCanonicalAmountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d, BigDecimal.TEN,
                List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("-0.01")
                ))));

        assertDegradedInvalid(result);
    }

    @Test
    void rapidTransferCountWithoutAmountDegradesWithoutCandidateFallback() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_COUNT, 2,
                        FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
                ))));

        assertDegradedInvalid(result);
    }

    @Test
    void rapidTransferAmountWithoutCountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                List.of(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00")))));

        assertDegradedInvalid(result);
    }

    @Test
    void canonicalCountConflictingWithTopLevelCountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                List.of(),
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 4))));

        assertDegradedInvalid(result);
    }

    @Test
    void canonicalAmountConflictingWithTopLevelRecentAmountDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("100.00"),
                List.of(),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("200.00")
                ))));

        assertDegradedInvalid(result);
    }

    @Test
    void canonicalAmountConsistencyIsSemanticNotScaleSensitive() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 2, 2.0d,
                new BigDecimal("20000.00"),
                List.of(),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.0")
                ))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
    }

    @Test
    void kafkaSerdeWrongCanonicalWireTypeRemainsDetectableAndDegrades() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        TransactionEnrichedEvent source = event(false, false, false, 5, 5.0d, BigDecimal.TEN,
                List.of(FraudFeatureContract.FLAG_HIGH_VELOCITY),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
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
    void invalidRapidTransferFraudCaseCandidateSnapshotTypeStillDegrades() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, "true"))));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
        assertThat(flatten(result)).doesNotContain("true");
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
    }

    @Test
    void missingRapidTransferFraudCaseCandidateDoesNotDegrade() {
        FraudSignalEvaluation result = engine.evaluate(context(event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(),
                Map.of())));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.statusReason()).isNull();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.evidence()).isEmpty();
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
                List.of(),
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
            List<String> featureFlags,
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
                recentTransactionCount,
                "PT1M",
                new Money(amount, "PLN"),
                "PT1M",
                velocityPerMinute,
                base.merchantFrequency7d(),
                deviceNovelty,
                countryMismatch,
                proxyOrVpn,
                featureFlags,
                featureSnapshot
        );
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
