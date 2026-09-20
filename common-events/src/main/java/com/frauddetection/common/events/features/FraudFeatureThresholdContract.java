package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FraudFeatureThresholdContract {
    public static final int RAPID_TRANSFER_MIN_COUNT = 2;
    public static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = BigDecimal.valueOf(20_000);

    private FraudFeatureThresholdContract() {
    }

    public static boolean isRapidTransferPlnBurst(int transactionCount, BigDecimal amountPln) {
        Objects.requireNonNull(amountPln, "amountPln is required");
        if (!FraudFeatureValueBoundsContract.isWithinCountBounds(transactionCount)
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(amountPln)) {
            throw new IllegalArgumentException("rapid transfer facts are outside canonical bounds");
        }
        return transactionCount >= RAPID_TRANSFER_MIN_COUNT
                && amountPln.compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
    }

    public static boolean isRapidTransferPlnBurst(Map<String, Object> featureSnapshot) {
        if (featureSnapshot == null || featureSnapshot.isEmpty()) {
            return false;
        }
        Integer count = integer(featureSnapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT));
        BigDecimal amountPln = decimal(featureSnapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN));
        if (count == null || amountPln == null) {
            return false;
        }
        if (!FraudFeatureValueBoundsContract.isWithinCountBounds(count)
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(amountPln)) {
            return false;
        }
        if (!FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                string(featureSnapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW))
        ) || !FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                string(featureSnapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW))
        )) {
            return false;
        }
        if (!hasRapidTransferEvidenceIds(featureSnapshot.get(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))) {
            return false;
        }
        return isRapidTransferPlnBurst(count, amountPln);
    }

    private static boolean hasRapidTransferEvidenceIds(Object value) {
        if (!(value instanceof List<?> ids) || ids.size() < RAPID_TRANSFER_MIN_COUNT) {
            return false;
        }
        for (Object id : ids) {
            if (!(id instanceof String text) || text.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Integer integer(Object value) {
        return value instanceof Integer integer ? integer : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }
}
