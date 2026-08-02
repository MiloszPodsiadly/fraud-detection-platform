package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;

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
            if (actual instanceof Integer integer && !VelocityFeatureContract.isWithinBounds(integer)) {
                return false;
            }
            if (actual instanceof BigDecimal decimal && !VelocityFeatureContract.isWithinBounds(decimal)) {
                return false;
            }
            if (actual instanceof Double doubleValue && !VelocityFeatureContract.isWithinBounds(doubleValue)) {
                return false;
            }
            if (actual instanceof String window && !isCanonicalVelocityWindow(window)) {
                return false;
            }
        }
        return true;
    }

    private boolean presentInputRelationshipsAreConsistent(VelocityInputs inputs) {
        if (inputs.recentTransactionCount().status() != FeatureSnapshotValueStatus.PRESENT
                || inputs.transactionVelocityPerMinute().status() != FeatureSnapshotValueStatus.PRESENT) {
            return true;
        }
        return VelocityFeatureContract.isRateConsistentWithCount(
                inputs.recentTransactionCount().value(),
                inputs.transactionVelocityPerMinute().value()
        );
    }

    private boolean isCanonicalVelocityWindow(String value) {
        if (value.length() > MAX_WINDOW_TEXT_LENGTH) {
            return false;
        }
        return VelocityFeatureContract.isCanonicalWindowText(value);
    }
}
