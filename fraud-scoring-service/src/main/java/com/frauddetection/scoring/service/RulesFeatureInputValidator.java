package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public final class RulesFeatureInputValidator {

    private RulesFeatureInputValidator() {
    }

    public static void requireValid(TransactionEnrichedEvent event) {
        Objects.requireNonNull(event, "event is required");
        if (!isValid(event, new FeatureSnapshotReader(snapshot(event)))) {
            throw new RulesFeatureInputValidationException();
        }
    }

    public static boolean isValid(TransactionEnrichedEvent event, FeatureSnapshotReader reader) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(reader, "reader is required");
        FeatureSnapshotValue<Integer> recentTransactionCount =
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        FeatureSnapshotValue<BigDecimal> recentAmountSumPln =
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        FeatureSnapshotValue<Integer> rapidTransferCount =
                reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT);
        FeatureSnapshotValue<BigDecimal> rapidTransferTotalPln =
                reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN);
        FeatureSnapshotValue<Boolean> rapidTransferFraudCaseCandidate =
                reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE);

        failFastForAdapterDefects(
                recentTransactionCount,
                recentAmountSumPln,
                rapidTransferCount,
                rapidTransferTotalPln,
                rapidTransferFraudCaseCandidate
        );
        if (hasInvalidType(
                recentTransactionCount,
                recentAmountSumPln,
                rapidTransferCount,
                rapidTransferTotalPln,
                rapidTransferFraudCaseCandidate
        )) {
            return false;
        }
        if (!validCount(recentTransactionCount)
                || !validAmount(recentAmountSumPln)
                || !validCount(rapidTransferCount)
                || !validAmount(rapidTransferTotalPln)) {
            return false;
        }
        if (present(rapidTransferCount) != present(rapidTransferTotalPln)) {
            return false;
        }
        if (!validTopLevelCount(event.recentTransactionCount())
                || !validTopLevelAmount(event.recentAmountSum() == null ? null : event.recentAmountSum().amount())) {
            return false;
        }
        if (present(recentTransactionCount)
                && event.recentTransactionCount() != null
                && !recentTransactionCount.value().equals(event.recentTransactionCount())) {
            return false;
        }
        return !present(recentAmountSumPln)
                || event.recentAmountSum() == null
                || !isPln(event.recentAmountSum().currency())
                || recentAmountSumPln.value().compareTo(event.recentAmountSum().amount()) == 0;
    }

    private static Map<String, Object> snapshot(TransactionEnrichedEvent event) {
        return event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
    }

    private static void failFastForAdapterDefects(FeatureSnapshotValue<?>... values) {
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.WRONG_ACCESSOR) {
                throw new IllegalStateException("adapter feature accessor mismatch");
            }
            if (value.status() == FeatureSnapshotValueStatus.NOT_ALLOWED) {
                throw new IllegalStateException("adapter feature access policy violation");
            }
        }
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
        return !present(value) || VelocityFeatureContract.isWithinBounds(value.value());
    }

    private static boolean validAmount(FeatureSnapshotValue<BigDecimal> value) {
        return !present(value) || VelocityFeatureContract.isWithinBounds(value.value());
    }

    private static boolean validTopLevelCount(Integer value) {
        return value == null || VelocityFeatureContract.isWithinBounds(value);
    }

    private static boolean validTopLevelAmount(BigDecimal value) {
        return value == null || VelocityFeatureContract.isWithinBounds(value);
    }

    private static boolean isPln(String currency) {
        return "PLN".equalsIgnoreCase(currency);
    }

    private static boolean present(FeatureSnapshotValue<?> value) {
        return value.status() == FeatureSnapshotValueStatus.PRESENT;
    }
}
