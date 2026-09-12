package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.model.SupportedCurrencyContract;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RulesFeatureInputValidator {

    private RulesFeatureInputValidator() {
    }

    public static void requireValid(TransactionEnrichedEvent event) {
        requireValidInput(event);
    }

    public static ValidatedRulesInput requireValidInput(TransactionEnrichedEvent event) {
        return requireValidInput(event, new FeatureSnapshotReader(snapshot(event)));
    }

    public static ValidatedRulesInput requireValidInput(TransactionEnrichedEvent event, FeatureSnapshotReader reader) {
        RulesInputValidationResult validation = validate(event, reader);
        validation.requireNoAdapterDefect();
        if (!validation.valid()) {
            throw new RulesFeatureInputValidationException();
        }
        return new ValidatedRulesInput(event);
    }

    public static boolean isValid(TransactionEnrichedEvent event, FeatureSnapshotReader reader) {
        RulesInputValidationResult validation = validate(event, reader);
        validation.requireNoAdapterDefect();
        return validation.valid();
    }

    public static RulesInputValidationResult validate(TransactionEnrichedEvent event, FeatureSnapshotReader reader) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(reader, "reader is required");
        FeatureSnapshotValue<Integer> recentTransactionCount =
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        FeatureSnapshotValue<String> recentTransactionCountWindow =
                reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW);
        FeatureSnapshotValue<Double> transactionVelocityPerMinute =
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE);
        FeatureSnapshotValue<BigDecimal> recentAmountSumPln =
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        FeatureSnapshotValue<String> recentAmountSumWindow =
                reader.stringValue(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW);
        FeatureSnapshotValue<Integer> rapidTransferCount =
                reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT);
        FeatureSnapshotValue<BigDecimal> rapidTransferTotalPln =
                reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN);
        FeatureSnapshotValue<String> rapidTransferWindow =
                reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_WINDOW);
        FeatureSnapshotValue<Boolean> rapidTransferFraudCaseCandidate =
                reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE);

        Optional<RulesInputValidationStatus> adapterDefect = adapterDefect(
                recentTransactionCount,
                recentTransactionCountWindow,
                transactionVelocityPerMinute,
                recentAmountSumPln,
                recentAmountSumWindow,
                rapidTransferCount,
                rapidTransferTotalPln,
                rapidTransferWindow,
                rapidTransferFraudCaseCandidate
        );
        if (adapterDefect.isPresent()) {
            return RulesInputValidationResult.invalid(adapterDefect.get());
        }
        if (hasInvalidType(
                recentTransactionCount,
                recentTransactionCountWindow,
                transactionVelocityPerMinute,
                recentAmountSumPln,
                recentAmountSumWindow,
                rapidTransferCount,
                rapidTransferTotalPln,
                rapidTransferWindow,
                rapidTransferFraudCaseCandidate
        )) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_TYPE);
        }
        if (!validCount(recentTransactionCount)
                || !validRate(transactionVelocityPerMinute)
                || !validAmount(recentAmountSumPln)
                || !validCount(rapidTransferCount)
                || !validAmount(rapidTransferTotalPln)) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.OUT_OF_BOUNDS);
        }
        RulesInputValidationResult canonicalCountWindow =
                validateCanonicalWindow(recentTransactionCount, recentTransactionCountWindow);
        if (!canonicalCountWindow.valid()) {
            return canonicalCountWindow;
        }
        RulesInputValidationResult canonicalCountRate =
                validateCountRateConsistency(recentTransactionCount, transactionVelocityPerMinute);
        if (!canonicalCountRate.valid()) {
            return canonicalCountRate;
        }
        RulesInputValidationResult canonicalAmountWindow =
                validateCanonicalWindow(recentAmountSumPln, recentAmountSumWindow);
        if (!canonicalAmountWindow.valid()) {
            return canonicalAmountWindow;
        }
        RulesInputValidationResult rapidCountWindow = validateCanonicalWindow(rapidTransferCount, rapidTransferWindow);
        if (!rapidCountWindow.valid()) {
            return rapidCountWindow;
        }
        RulesInputValidationResult rapidAmountWindow = validateCanonicalWindow(rapidTransferTotalPln, rapidTransferWindow);
        if (!rapidAmountWindow.valid()) {
            return rapidAmountWindow;
        }
        if (!validTopLevelCount(event.recentTransactionCount())
                || !validTopLevelRate(event.transactionVelocityPerMinute())
                || !validTopLevelAmount(event.recentAmountSum() == null ? null : event.recentAmountSum().amount())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.OUT_OF_BOUNDS);
        }
        RulesInputValidationResult topLevelCountWindow = validateTopLevelCountWindow(event);
        if (!topLevelCountWindow.valid()) {
            return topLevelCountWindow;
        }
        RulesInputValidationResult topLevelCountRate = validateTopLevelCountRateConsistency(event);
        if (!topLevelCountRate.valid()) {
            return topLevelCountRate;
        }
        RulesInputValidationResult topLevelAmountWindow = validateTopLevelAmountWindow(event);
        if (!topLevelAmountWindow.valid()) {
            return topLevelAmountWindow;
        }
        if (!validSupportedCurrency(event.transactionAmount())
                || !validSupportedCurrency(event.recentAmountSum())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.UNSUPPORTED_CURRENCY_BASIS);
        }
        if (present(recentTransactionCount)
                && event.recentTransactionCount() != null
                && !recentTransactionCount.value().equals(event.recentTransactionCount())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        if (present(transactionVelocityPerMinute)
                && event.transactionVelocityPerMinute() != null
                && !ratesMatch(transactionVelocityPerMinute.value(), event.transactionVelocityPerMinute())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        if (present(recentAmountSumPln)
                && event.recentAmountSum() != null
                && isPln(event.recentAmountSum().currency())
                && recentAmountSumPln.value().compareTo(event.recentAmountSum().amount()) != 0) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        return RulesInputValidationResult.ok();
    }

    private static Map<String, Object> snapshot(TransactionEnrichedEvent event) {
        return event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
    }

    private static Optional<RulesInputValidationStatus> adapterDefect(FeatureSnapshotValue<?>... values) {
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.WRONG_ACCESSOR) {
                return Optional.of(RulesInputValidationStatus.ADAPTER_ACCESSOR_DEFECT);
            }
            if (value.status() == FeatureSnapshotValueStatus.NOT_ALLOWED) {
                return Optional.of(RulesInputValidationStatus.ACCESS_POLICY_DEFECT);
            }
        }
        return Optional.empty();
    }

    private static boolean hasInvalidType(FeatureSnapshotValue<?>... values) {
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.INVALID_TYPE) {
                return true;
            }
        }
        return false;
    }

    private static boolean validCount(FeatureSnapshotValue<Integer> value) {
        return !present(value) || FraudFeatureValueBoundsContract.isWithinCountBounds(value.value());
    }

    private static boolean validAmount(FeatureSnapshotValue<BigDecimal> value) {
        return !present(value) || FraudFeatureValueBoundsContract.isWithinAmountBounds(value.value());
    }

    private static boolean validRate(FeatureSnapshotValue<Double> value) {
        return !present(value) || FraudFeatureValueBoundsContract.isWithinRatePerMinuteBounds(value.value());
    }

    private static boolean validTopLevelCount(Integer value) {
        return value == null || FraudFeatureValueBoundsContract.isWithinCountBounds(value);
    }

    private static boolean validTopLevelAmount(BigDecimal value) {
        return value == null || FraudFeatureValueBoundsContract.isWithinAmountBounds(value);
    }

    private static boolean validTopLevelRate(Double value) {
        return value == null || FraudFeatureValueBoundsContract.isWithinRatePerMinuteBounds(value);
    }

    private static boolean validSupportedCurrency(Money money) {
        return money == null || SupportedCurrencyContract.isSupported(money.currency());
    }

    private static RulesInputValidationResult validateTopLevelCountWindow(TransactionEnrichedEvent event) {
        if (event.recentTransactionCountWindow() != null
                && !FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(event.recentTransactionCountWindow())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_WINDOW);
        }
        if (event.recentTransactionCount() != null && event.recentTransactionCountWindow() == null) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        }
        return RulesInputValidationResult.ok();
    }

    private static RulesInputValidationResult validateTopLevelAmountWindow(TransactionEnrichedEvent event) {
        if (event.recentAmountSumWindow() != null
                && !FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(event.recentAmountSumWindow())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_WINDOW);
        }
        if (event.recentAmountSum() == null && event.recentAmountSumWindow() != null) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        }
        if (event.recentAmountSum() != null && event.recentAmountSumWindow() == null) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        }
        return RulesInputValidationResult.ok();
    }

    private static RulesInputValidationResult validateCanonicalWindow(
            FeatureSnapshotValue<?> fact,
            FeatureSnapshotValue<String> window
    ) {
        if (present(window) && !FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(window.value())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_WINDOW);
        }
        if (present(fact) && !present(window)) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        }
        return RulesInputValidationResult.ok();
    }

    private static RulesInputValidationResult validateCountRateConsistency(
            FeatureSnapshotValue<Integer> count,
            FeatureSnapshotValue<Double> rate
    ) {
        if (present(count)
                && present(rate)
                && !FraudFeatureValueBoundsContract.isRateConsistentWithCount(count.value(), rate.value())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        return RulesInputValidationResult.ok();
    }

    private static RulesInputValidationResult validateTopLevelCountRateConsistency(TransactionEnrichedEvent event) {
        if (event.recentTransactionCount() != null
                && event.transactionVelocityPerMinute() != null
                && !FraudFeatureValueBoundsContract.isRateConsistentWithCount(
                event.recentTransactionCount(),
                event.transactionVelocityPerMinute()
        )) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        return RulesInputValidationResult.ok();
    }

    private static boolean ratesMatch(double left, double right) {
        return Math.abs(left - right) <= FraudFeatureValueBoundsContract.RATE_CONSISTENCY_TOLERANCE;
    }

    private static boolean isPln(String currency) {
        return "PLN".equalsIgnoreCase(currency);
    }

    private static boolean present(FeatureSnapshotValue<?> value) {
        return value.status() == FeatureSnapshotValueStatus.PRESENT;
    }
}
