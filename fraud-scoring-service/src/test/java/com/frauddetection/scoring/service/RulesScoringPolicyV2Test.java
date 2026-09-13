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
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RulesScoringPolicyV2Test {
    private final RulesScoringPolicyV2 policy = new RulesScoringPolicyV2(
            new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED)
    );

    @Test
    void lowBaselineUsesV2LineageAndCanonicalSnapshotOnly() {
        FraudScoreResult result = score(snapshot());

        assertThat(result.fraudScore()).isEqualTo(0.05d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.modelVersion()).isEqualTo("v2");
        assertThat(result.featureSnapshot())
                .containsKeys(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW,
                        FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW,
                        FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
                        FraudFeatureContract.MERCHANT_FREQUENCY_7D,
                        FraudFeatureContract.DEVICE_NOVELTY,
                        FraudFeatureContract.COUNTRY_MISMATCH,
                        FraudFeatureContract.PROXY_OR_VPN_DETECTED,
                        FraudFeatureContract.CURRENCY
                )
                .doesNotContainKeys(
                        "featureFlags",
                        "rapidTransferFraudCaseCandidate",
                        FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN
                );
        assertThat(result.scoreDetails()).doesNotContainKey("featureFlags");
    }

    @Test
    void eachCanonicalBooleanAndMerchantSignalContributesOnceInDeterministicOrder() {
        FraudScoreResult result = score(snapshot()
                .with(FraudFeatureContract.DEVICE_NOVELTY, true)
                .with(FraudFeatureContract.COUNTRY_MISMATCH, true)
                .with(FraudFeatureContract.PROXY_OR_VPN_DETECTED, true)
                .with(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 5)
        );

        assertThat(result.fraudScore()).isEqualTo(0.71d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.reasonCodes()).containsExactly(
                ReasonCode.DEVICE_NOVELTY.wireValue(),
                ReasonCode.COUNTRY_MISMATCH.wireValue(),
                ReasonCode.PROXY_OR_VPN.wireValue(),
                ReasonCode.MERCHANT_CONCENTRATION.wireValue()
        );
    }

    @Test
    void velocityAmountAndRapidBurstUseCanonicalFactsWithoutSemanticDoubleCounting() {
        FraudScoreResult result = score(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
                .with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
        );

        assertThat(result.fraudScore()).isEqualTo(0.99d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.reasonCodes()).containsExactly(
                ReasonCode.HIGH_VELOCITY.wireValue(),
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue()
        );
        assertThat(result.scoreDetails().keySet())
                .filteredOn(key -> key.endsWith("RulesV2Weight"))
                .containsExactly(
                        "highVelocityRulesV2Weight",
                        "highAmountActivityRulesV2Weight",
                        "rapidPln20kBurstRulesV2Weight"
                );
    }

    @Test
    void currentTransactionAmountIsDiagnosticOnly() {
        FraudScoreResult result = score(snapshot()
                .with(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("1000.00"))
        );

        assertThat(result.fraudScore()).isEqualTo(0.05d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
        assertThat(result.scoreDetails()).containsEntry("highTransactionAmountDiagnostic", true);
    }

    @Test
    void riskBoundariesUseConfiguredThresholds() {
        assertThat(score(snapshot()
                .with(FraudFeatureContract.DEVICE_NOVELTY, true)
                .with(FraudFeatureContract.COUNTRY_MISMATCH, true)).riskLevel())
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(score(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
                .with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("5000.00"))
                .with(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 5)).riskLevel())
                .isEqualTo(RiskLevel.HIGH);
        assertThat(score(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 2.0d)
                .with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
                .with(FraudFeatureContract.COUNTRY_MISMATCH, true)).riskLevel())
                .isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void legacyInputsCannotInfluenceRulesV2Output() {
        SnapshotBuilder canonicalLow = snapshot();
        FraudScoreResult baseline = score(canonicalLow);
        TransactionEnrichedEvent legacyOnly = event(canonicalLow.withExtras(Map.of(
                "featureFlags", List.of(
                        FraudFeatureContract.FLAG_HIGH_VELOCITY,
                        FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY,
                        FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST
                ),
                "rapidTransferFraudCaseCandidate", true,
                FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN, new BigDecimal("20000.00")
        )), source -> new TransactionEnrichedEvent(
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
                99,
                "PT1M",
                new Money(new BigDecimal("999999.00"), "PLN"),
                "PT1M",
                99.0d,
                99,
                true,
                true,
                true,
                source.featureSnapshot()
        ));

        FraudScoreResult withLegacyOnly = policy.score(RulesV2InputValidator.requireValidInput(legacyOnly));

        assertThat(withLegacyOnly.fraudScore()).isEqualTo(baseline.fraudScore());
        assertThat(withLegacyOnly.reasonCodes()).isEqualTo(baseline.reasonCodes());
        assertThat(withLegacyOnly.riskLevel()).isEqualTo(baseline.riskLevel());
    }

    @Test
    void validationResultForOneInputCannotAuthorizeAnotherRequest() {
        RulesV2ValidatedInput lowInput = RulesV2InputValidator.requireValidInput(event(snapshot()));
        TransactionEnrichedEvent highEvent = event(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
                .with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
        );

        FraudScoreResult result = policy.score(lowInput);

        assertThat(result.fraudScore()).isEqualTo(0.05d);
        assertThat(policy.score(RulesV2InputValidator.requireValidInput(highEvent)).fraudScore())
                .isGreaterThan(result.fraudScore());
    }

    @Test
    void mutatingOriginalFeatureMapAfterValidationDoesNotAffectValidatedInput() {
        Map<String, Object> mutableSnapshot = new LinkedHashMap<>(snapshot().values);
        RulesV2ValidatedInput input = RulesV2InputValidator.requireValidInput(new FeatureSnapshotReader(mutableSnapshot));

        mutableSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5);
        mutableSnapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d);
        mutableSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"));

        FraudScoreResult result = policy.score(input);

        assertThat(result.fraudScore()).isEqualTo(0.05d);
        assertThat(input.featureSnapshot()).containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1);
    }

    @Test
    void validatedInputDoesNotExposeRawEventOrPublicConstructor() {
        assertThat(RulesV2ValidatedInput.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .doesNotContain(TransactionEnrichedEvent.class.getName());
        assertThat(RulesV2ValidatedInput.class.getConstructors()).isEmpty();
        for (Constructor<?> constructor : RulesV2ValidatedInput.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
        }
    }

    @Test
    void invalidCanonicalFactsFailClosed() {
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.NaN),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.POSITIVE_INFINITY),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.NEGATIVE_INFINITY),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, -1.0d),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 1_000_000.1d),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, -1),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("-0.01")),
                RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertInvalid(snapshot().with(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT5M"),
                RulesInputValidationStatus.INVALID_WINDOW);
        assertInvalid(snapshot().without(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW),
                RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        assertInvalid(snapshot().with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 0.0d),
                RulesInputValidationStatus.INCONSISTENT_FACTS);
        assertInvalid(snapshot().with(FraudFeatureContract.CURRENCY, "JPY"),
                RulesInputValidationStatus.UNSUPPORTED_CURRENCY_BASIS);
        assertInvalid(snapshot().with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, "1"),
                RulesInputValidationStatus.INVALID_TYPE);
        assertInvalid(snapshot().with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, Map.of("nested", 1)),
                RulesInputValidationStatus.INVALID_TYPE);
    }

    @Test
    void zeroRateIsValidWhenCountIsZero() {
        FraudScoreResult result = score(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 0)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 0.0d)
        );

        assertThat(result.fraudScore()).isEqualTo(0.05d);
    }

    private void assertInvalid(SnapshotBuilder snapshot, RulesInputValidationStatus status) {
        TransactionEnrichedEvent event = event(snapshot);

        assertThat(RulesV2InputValidator.validate(event).status()).isEqualTo(status);
        assertThatThrownBy(() -> RulesV2InputValidator.requireValidInput(event))
                .isInstanceOf(RulesFeatureInputValidationException.class);
    }

    private FraudScoreResult score(SnapshotBuilder snapshot) {
        return policy.score(RulesV2InputValidator.requireValidInput(event(snapshot)));
    }

    private SnapshotBuilder snapshot() {
        return new SnapshotBuilder(Map.ofEntries(
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1),
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 1.0d),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00")),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("100.00")),
                Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 1),
                Map.entry(FraudFeatureContract.DEVICE_NOVELTY, false),
                Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, false),
                Map.entry(FraudFeatureContract.CURRENCY, "PLN")
        ));
    }

    private TransactionEnrichedEvent event(SnapshotBuilder snapshot) {
        return event(snapshot, UnaryOperator.identity());
    }

    private TransactionEnrichedEvent event(
            SnapshotBuilder snapshot,
            UnaryOperator<TransactionEnrichedEvent> customizer
    ) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        TransactionEnrichedEvent canonical = new TransactionEnrichedEvent(
                "evt-v2",
                "txn-v2",
                "corr-v2",
                base.customerId(),
                base.accountId(),
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(new BigDecimal("100.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                1,
                "PT1M",
                new Money(new BigDecimal("100.00"), "PLN"),
                "PT1M",
                1.0d,
                1,
                false,
                false,
                false,
                snapshot.values
        );
        return customizer.apply(canonical);
    }

    private record SnapshotBuilder(Map<String, Object> values) {
        SnapshotBuilder with(String key, Object value) {
            Map<String, Object> updated = new LinkedHashMap<>(values);
            updated.put(key, value);
            return new SnapshotBuilder(Map.copyOf(updated));
        }

        SnapshotBuilder withExtras(Map<String, Object> extras) {
            Map<String, Object> updated = new LinkedHashMap<>(values);
            updated.putAll(extras);
            return new SnapshotBuilder(Map.copyOf(updated));
        }

        SnapshotBuilder without(String key) {
            Map<String, Object> updated = new LinkedHashMap<>(values);
            updated.remove(key);
            return new SnapshotBuilder(Map.copyOf(updated));
        }
    }
}
