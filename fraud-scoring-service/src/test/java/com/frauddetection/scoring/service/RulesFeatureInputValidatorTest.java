package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
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
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                source.featureFlags(),
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
