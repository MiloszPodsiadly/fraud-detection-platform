package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;

final class VelocityInputValidator {
    private static final int MAX_WINDOW_TEXT_LENGTH = 32;

    VelocityInputValidation validate(VelocityInputs inputs) {
        boolean missing = false;
        for (FeatureSnapshotValue<?> value : inputs.values()) {
            if (value.status() == FeatureSnapshotValueStatus.WRONG_ACCESSOR
                    || value.status() == FeatureSnapshotValueStatus.NOT_ALLOWED
                    || value.status() == FeatureSnapshotValueStatus.INVALID_TYPE) {
                return VelocityInputValidation.degraded(VelocitySignalReasonCode.VELOCITY_FEATURE_TYPE_INVALID);
            }
            if (value.status() == FeatureSnapshotValueStatus.MISSING) {
                missing = true;
            }
        }
        if (!presentInputDomainsAreValid(inputs)) {
            return VelocityInputValidation.degraded(VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
        }
        if (!presentInputRelationshipsAreConsistent(inputs)) {
            return VelocityInputValidation.degraded(VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT);
        }
        if (missing) {
            return VelocityInputValidation.unavailable(VelocitySignalReasonCode.VELOCITY_FEATURES_UNAVAILABLE);
        }
        return VelocityInputValidation.available(new ValidatedVelocityInputs(
                inputs.recentTransactionCount().value(),
                inputs.recentAmountSumPln().value(),
                inputs.transactionVelocityPerMinute().value()
        ));
    }

    private boolean presentInputDomainsAreValid(VelocityInputs inputs) {
        for (FeatureSnapshotValue<?> value : inputs.values()) {
            if (value.status() != FeatureSnapshotValueStatus.PRESENT) {
                continue;
            }
            Object actual = value.value();
            if (actual instanceof Integer integer && integer < 0) {
                return false;
            }
            if (actual instanceof BigDecimal decimal && decimal.signum() < 0) {
                return false;
            }
            if (actual instanceof Double doubleValue && (!Double.isFinite(doubleValue) || doubleValue < 0.0d)) {
                return false;
            }
            if (actual instanceof String window && !isCanonicalVelocityWindow(window)) {
                return false;
            }
        }
        return true;
    }

    private boolean presentInputRelationshipsAreConsistent(VelocityInputs inputs) {
        FeatureSnapshotValue<Integer> count = inputs.recentTransactionCount();
        if (count.status() != FeatureSnapshotValueStatus.PRESENT || count.value() != 0) {
            return true;
        }
        FeatureSnapshotValue<BigDecimal> amount = inputs.recentAmountSumPln();
        if (amount.status() == FeatureSnapshotValueStatus.PRESENT && amount.value().signum() > 0) {
            return false;
        }
        FeatureSnapshotValue<Double> rate = inputs.transactionVelocityPerMinute();
        return rate.status() != FeatureSnapshotValueStatus.PRESENT
                || Double.compare(rate.value(), 0.0d) <= 0;
    }

    private boolean isCanonicalVelocityWindow(String value) {
        if (value.length() > MAX_WINDOW_TEXT_LENGTH) {
            return false;
        }
        try {
            return FraudFeatureThresholdContract.VELOCITY_V1_OBSERVATION_WINDOW.equals(Duration.parse(value));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
