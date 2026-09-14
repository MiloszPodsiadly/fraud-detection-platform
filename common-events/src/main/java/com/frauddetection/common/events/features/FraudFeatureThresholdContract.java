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

    public static boolean isRapidTransferPlnBurst(int rapidTransferCount, BigDecimal rapidTransferTotalPln) {
        Objects.requireNonNull(rapidTransferTotalPln, "rapidTransferTotalPln is required");
        if (rapidTransferCount < 0 || rapidTransferTotalPln.signum() < 0) {
            throw new IllegalArgumentException("rapid transfer facts must be non-negative");
        }
        return rapidTransferCount >= RAPID_TRANSFER_MIN_COUNT
                && rapidTransferTotalPln.compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
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
        return value instanceof List<?> ids && ids.size() >= RAPID_TRANSFER_MIN_COUNT;
    }

    private static Integer integer(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return (int) longValue;
            }
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }
}
