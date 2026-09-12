package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulesFeatureInputValidatorTest {

    @Test
    void validFactsReturnTypedValidStatus() {
        assertThat(validate(event(Map.of())).status()).isEqualTo(RulesInputValidationStatus.VALID);
    }

    @Test
    void malformedCanonicalFactReturnsInvalidTypeStatus() {
        RulesInputValidationResult result = validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "5"
        )));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.INVALID_TYPE);
        assertThat(result.toString()).doesNotContain("5");
    }

    @Test
    void nonCanonicalWindowReturnsInvalidWindowStatusWithoutRawWindow() {
        RulesInputValidationResult result = validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D"
        )));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.INVALID_WINDOW);
        assertThat(result.toString()).doesNotContain("P1D");
    }

    @Test
    void outOfBoundsFactReturnsOutOfBoundsStatusWithoutRawCount() {
        RulesInputValidationResult result = validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, -1,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
        )));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertThat(result.toString()).doesNotContain("-1");
    }

    @Test
    void inconsistentCanonicalAndTopLevelFactsReturnInconsistentFactsStatus() {
        RulesInputValidationResult result = validate(withTopLevelCount(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 3,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
        )), 4));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.INCONSISTENT_FACTS);
        assertThat(result.toString()).doesNotContain("3").doesNotContain("4");
    }

    @Test
    void missingPairedWindowReturnsIncompleteFactPairStatus() {
        RulesInputValidationResult result = validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5
        )));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        assertThat(result.toString()).doesNotContain("5");
    }

    @Test
    void unsupportedCurrencyBasisReturnsUnsupportedCurrencyStatus() {
        RulesInputValidationResult result = validate(withTransactionCurrency(event(Map.of()), "XXX"));

        assertThat(result.status()).isEqualTo(RulesInputValidationStatus.UNSUPPORTED_CURRENCY_BASIS);
        assertThat(result.toString()).doesNotContain("XXX");
    }

    @Test
    void adapterAccessorDefectStatusFailsFastAsProgrammingError() {
        RulesInputValidationResult result =
                RulesInputValidationResult.invalid(RulesInputValidationStatus.ADAPTER_ACCESSOR_DEFECT);

        assertThatThrownBy(result::requireNoAdapterDefect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter feature accessor mismatch");
    }

    @Test
    void accessPolicyDefectStatusFailsFastAsConfigurationError() {
        RulesInputValidationResult result =
                RulesInputValidationResult.invalid(RulesInputValidationStatus.ACCESS_POLICY_DEFECT);

        assertThatThrownBy(result::requireNoAdapterDefect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter feature access policy violation");
    }

    @Test
    void primaryExceptionMessageRemainsStableAndBounded() {
        TransactionEnrichedEvent event = withTransactionCurrency(event(Map.of()), "JPY");

        assertThatThrownBy(() -> RulesFeatureInputValidator.requireValid(event))
                .isInstanceOf(RulesFeatureInputValidationException.class)
                .hasMessage("RULES_FEATURE_INPUT_INVALID")
                .hasMessageNotContaining("JPY")
                .hasMessageNotContaining("txn-validator");
    }

    @Test
    void transactionVelocityPerMinuteRejectsNonFiniteAndNegativeValues() {
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.NaN
        ))).status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.POSITIVE_INFINITY
        ))).status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.NEGATIVE_INFINITY
        ))).status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, -0.1d
        ))).status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
    }

    @Test
    void transactionVelocityPerMinuteValidatesZeroAndExplicitBounds() {
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 0.0d
        ))).status()).isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE,
                FraudFeatureValueBoundsContract.MAX_TRANSACTION_VELOCITY_PER_MINUTE
        ))).status()).isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE,
                FraudFeatureValueBoundsContract.MAX_TRANSACTION_VELOCITY_PER_MINUTE + 0.1d
        ))).status()).isEqualTo(RulesInputValidationStatus.OUT_OF_BOUNDS);
    }

    @Test
    void transactionVelocityPerMinuteValidatesPt1mCountRateConsistencyWhenBothFactsArePresent() {
        assertThat(validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d
        ))).status()).isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 4.0d
        ))).status()).isEqualTo(RulesInputValidationStatus.INCONSISTENT_FACTS);
    }

    @Test
    void transactionVelocityPerMinuteDoesNotTurnMissingCountOrRateIntoZero() {
        assertThat(validate(event(Map.of(
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d
        ))).status()).isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"
        ))).status()).isEqualTo(RulesInputValidationStatus.VALID);
    }

    @Test
    void topLevelTransactionVelocityPerMinuteValidatesPt1mCountRateConsistency() {
        assertThat(validate(withTopLevelCountAndRate(event(Map.of()), 5, 5.0d)).status())
                .isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(withTopLevelCountAndRate(event(Map.of()), 5, 4.0d)).status())
                .isEqualTo(RulesInputValidationStatus.INCONSISTENT_FACTS);
    }

    @Test
    void presentTopLevelAmountWindowIsValidatedBeforeCurrencyEligibility() {
        TransactionEnrichedEvent invalidWindowUsd = withRecentAmount(event(Map.of()), "USD", "P1D");
        TransactionEnrichedEvent invalidWindowUnsupported = withRecentAmount(event(Map.of()), "JPY", "P1D");

        assertThat(validate(invalidWindowUsd).status()).isEqualTo(RulesInputValidationStatus.INVALID_WINDOW);
        assertThat(validate(invalidWindowUnsupported).status()).isEqualTo(RulesInputValidationStatus.INVALID_WINDOW);
    }

    @Test
    void topLevelAmountWindowMatrixCoversSupportedCurrenciesAndOrphanWindow() {
        assertThat(validate(withRecentAmount(event(Map.of()), "PLN", "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(withRecentAmount(event(Map.of()), "USD", "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(withRecentAmount(event(Map.of()), "EUR", "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(withRecentAmount(event(Map.of()), "GBP", "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.VALID);
        assertThat(validate(withRecentAmount(event(Map.of()), "JPY", "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.UNSUPPORTED_CURRENCY_BASIS);
        assertThat(validate(withOrphanRecentAmountWindow(event(Map.of()), "PT1M")).status())
                .isEqualTo(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
    }

    @Test
    void invalidPresentCanonicalAmountWindowCannotUseLegacyFlagFallback() {
        TransactionEnrichedEvent invalidCanonicalWithLegacyFlag = withFeatureFlags(event(Map.of(
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("6000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT5M"
        )), List.of(FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY));

        assertThat(validate(invalidCanonicalWithLegacyFlag).status())
                .isEqualTo(RulesInputValidationStatus.INVALID_WINDOW);
    }

    private RulesInputValidationResult validate(TransactionEnrichedEvent event) {
        return RulesFeatureInputValidator.validate(event, new FeatureSnapshotReader(event.featureSnapshot()));
    }

    private TransactionEnrichedEvent event(Map<String, Object> featureSnapshot) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction()
                .withTransactionId("txn-validator")
                .build();
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
                null,
                null,
                null,
                null,
                null,
                base.merchantFrequency7d(),
                false,
                false,
                false,
                List.of(),
                featureSnapshot
        );
    }

    private TransactionEnrichedEvent withTopLevelCount(TransactionEnrichedEvent source, Integer count) {
        return withTopLevelCountAndRate(source, count, source.transactionVelocityPerMinute());
    }

    private TransactionEnrichedEvent withTopLevelCountAndRate(
            TransactionEnrichedEvent source,
            Integer count,
            Double transactionVelocityPerMinute
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
                count,
                "PT1M",
                source.recentAmountSum(),
                source.recentAmountSumWindow(),
                transactionVelocityPerMinute,
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withRecentAmount(TransactionEnrichedEvent source, String currency, String window) {
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
                new Money(new BigDecimal("6000.00"), currency),
                window,
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withOrphanRecentAmountWindow(TransactionEnrichedEvent source, String window) {
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
                null,
                window,
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withFeatureFlags(TransactionEnrichedEvent source, List<String> featureFlags) {
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
                featureFlags,
                source.featureSnapshot()
        );
    }

    private TransactionEnrichedEvent withTransactionCurrency(TransactionEnrichedEvent source, String currency) {
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                new Money(source.transactionAmount().amount(), currency),
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
                source.featureSnapshot()
        );
    }
}
