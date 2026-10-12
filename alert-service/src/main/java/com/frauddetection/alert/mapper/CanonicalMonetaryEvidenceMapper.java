package com.frauddetection.alert.mapper;

import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;

import java.math.BigDecimal;
import java.util.Map;

public final class CanonicalMonetaryEvidenceMapper {

    private CanonicalMonetaryEvidenceMapper() {
    }

    public static BigDecimal currentTransactionAmountPln(Map<String, Object> featureSnapshot) {
        if (featureSnapshot == null) {
            return null;
        }
        Object value = featureSnapshot.get(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN);
        if (!(value instanceof BigDecimal amountPln)
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(amountPln)) {
            return null;
        }
        return amountPln;
    }
}
