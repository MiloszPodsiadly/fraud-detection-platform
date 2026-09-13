package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.events.model.SupportedCurrencyContract;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RulesV2InputValidator {
    private RulesV2InputValidator() {
    }

    public static RulesV2ValidatedInput requireValidInput(TransactionEnrichedEvent event) {
        Objects.requireNonNull(event, "event is required");
        return requireValidInput(new FeatureSnapshotReader(snapshot(event)));
    }

    public static RulesV2ValidatedInput requireValidInput(FeatureSnapshotReader reader) {
        RulesV2FeatureValues values = read(reader);
        RulesInputValidationResult validation = validate(values);
        validation.requireNoAdapterDefect();
        if (!validation.valid()) {
            throw new RulesFeatureInputValidationException();
        }
        return new RulesV2ValidatedInput(
                values.recentTransactionCount().value(),
                values.recentTransactionCountWindow().value(),
                values.transactionVelocityPerMinute().value(),
                values.recentAmountSumPln().value(),
                values.recentAmountSumWindow().value(),
                values.currentTransactionAmountPln().value(),
                values.merchantFrequency7d().value(),
                values.deviceNovelty().value(),
                values.countryMismatch().value(),
                values.proxyOrVpnDetected().value(),
                SupportedCurrencyContract.normalize(values.currency().value())
        );
    }

    public static RulesInputValidationResult validate(TransactionEnrichedEvent event) {
        Objects.requireNonNull(event, "event is required");
        return validate(new FeatureSnapshotReader(snapshot(event)));
    }

    public static RulesInputValidationResult validate(FeatureSnapshotReader reader) {
        return validate(read(reader));
    }

    private static RulesV2FeatureValues read(FeatureSnapshotReader reader) {
        Objects.requireNonNull(reader, "reader is required");
        return new RulesV2FeatureValues(
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT),
                reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW),
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE),
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                reader.stringValue(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW),
                reader.decimalValue(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN),
                reader.integerValue(FraudFeatureContract.MERCHANT_FREQUENCY_7D),
                reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY),
                reader.booleanValue(FraudFeatureContract.COUNTRY_MISMATCH),
                reader.booleanValue(FraudFeatureContract.PROXY_OR_VPN_DETECTED),
                reader.stringValue(FraudFeatureContract.CURRENCY)
        );
    }

    private static RulesInputValidationResult validate(RulesV2FeatureValues values) {
        Optional<RulesInputValidationStatus> adapterDefect = adapterDefect(values.values());
        if (adapterDefect.isPresent()) {
            return RulesInputValidationResult.invalid(adapterDefect.get());
        }
        if (hasStatus(FeatureSnapshotValueStatus.INVALID_TYPE, values.values())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_TYPE);
        }
        if (hasStatus(FeatureSnapshotValueStatus.MISSING, values.values())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCOMPLETE_FACT_PAIR);
        }
        if (!FraudFeatureValueBoundsContract.isWithinCountBounds(values.recentTransactionCount().value())
                || !FraudFeatureValueBoundsContract.isWithinRatePerMinuteBounds(values.transactionVelocityPerMinute().value())
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(values.recentAmountSumPln().value())
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(values.currentTransactionAmountPln().value())
                || !FraudFeatureValueBoundsContract.isWithinCountBounds(values.merchantFrequency7d().value())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.OUT_OF_BOUNDS);
        }
        if (!FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                values.recentTransactionCountWindow().value()
        ) || !FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                values.recentAmountSumWindow().value()
        )) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INVALID_WINDOW);
        }
        if (!FraudFeatureValueBoundsContract.isRateConsistentWithCount(
                values.recentTransactionCount().value(),
                values.transactionVelocityPerMinute().value()
        )) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.INCONSISTENT_FACTS);
        }
        if (!SupportedCurrencyContract.isSupported(values.currency().value())) {
            return RulesInputValidationResult.invalid(RulesInputValidationStatus.UNSUPPORTED_CURRENCY_BASIS);
        }
        return RulesInputValidationResult.ok();
    }

    private static Optional<RulesInputValidationStatus> adapterDefect(Iterable<FeatureSnapshotValue<?>> values) {
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

    private static boolean hasStatus(FeatureSnapshotValueStatus status, Iterable<FeatureSnapshotValue<?>> values) {
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == status) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> snapshot(TransactionEnrichedEvent event) {
        return event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
    }
}
