package com.frauddetection.common.events.features;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FraudFeatureContract {

    public static final String RECENT_TRANSACTION_COUNT = "recentTransactionCount";
    public static final String RECENT_TRANSACTION_COUNT_WINDOW = "recentTransactionCountWindow";
    public static final String RECENT_AMOUNT_SUM = "recentAmountSum";
    public static final String RECENT_AMOUNT_SUM_WINDOW = "recentAmountSumWindow";
    public static final String RECENT_AMOUNT_SUM_PLN = "recentAmountSumPln";
    public static final String CURRENT_TRANSACTION_AMOUNT_PLN = "currentTransactionAmountPln";
    public static final String TRANSACTION_VELOCITY_PER_MINUTE = "transactionVelocityPerMinute";
    public static final String TRANSACTION_VELOCITY_PER_HOUR = "transactionVelocityPerHour";
    public static final String TRANSACTION_VELOCITY_PER_DAY = "transactionVelocityPerDay";
    public static final String RECENT_AMOUNT_AVERAGE = "recentAmountAverage";
    public static final String RECENT_AMOUNT_STD_DEV = "recentAmountStdDev";
    public static final String AMOUNT_DEVIATION_FROM_USER_MEAN = "amountDeviationFromUserMean";
    public static final String MERCHANT_ENTROPY = "merchantEntropy";
    public static final String COUNTRY_ENTROPY = "countryEntropy";
    public static final String MERCHANT_FREQUENCY_7D = "merchantFrequency7d";
    public static final String DEVICE_NOVELTY = "deviceNovelty";
    public static final String COUNTRY_MISMATCH = "countryMismatch";
    public static final String PROXY_OR_VPN_DETECTED = "proxyOrVpnDetected";
    public static final String HIGH_RISK_FLAG_COUNT = "highRiskFlagCount";
    public static final String RAPID_TRANSFER_BURST = "rapidTransferBurst";
    public static final String RAPID_TRANSFER_TRANSACTION_IDS = "rapidTransferTransactionIds";
    public static final String CUSTOMER_SEGMENT = "customerSegment";
    public static final String MERCHANT_CATEGORY = "merchantCategory";
    public static final String CURRENCY = "currency";

    public static final List<String> ML_FEATURE_NAMES = List.of(
            RECENT_TRANSACTION_COUNT,
            RECENT_AMOUNT_SUM_PLN,
            TRANSACTION_VELOCITY_PER_MINUTE,
            MERCHANT_FREQUENCY_7D,
            DEVICE_NOVELTY,
            COUNTRY_MISMATCH,
            PROXY_OR_VPN_DETECTED,
            HIGH_RISK_FLAG_COUNT,
            RAPID_TRANSFER_BURST
    );

    public static final List<String> JAVA_ENRICHED_FEATURE_NAMES = List.of(
            RECENT_TRANSACTION_COUNT,
            RECENT_TRANSACTION_COUNT_WINDOW,
            RECENT_AMOUNT_SUM,
            RECENT_AMOUNT_SUM_WINDOW,
            RECENT_AMOUNT_SUM_PLN,
            CURRENT_TRANSACTION_AMOUNT_PLN,
            RAPID_TRANSFER_TRANSACTION_IDS,
            TRANSACTION_VELOCITY_PER_MINUTE,
            MERCHANT_FREQUENCY_7D,
            DEVICE_NOVELTY,
            COUNTRY_MISMATCH,
            PROXY_OR_VPN_DETECTED,
            CUSTOMER_SEGMENT,
            MERCHANT_CATEGORY,
            CURRENCY
    );

    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String TYPE_INTEGER = "INTEGER";
    public static final String TYPE_LONG = "LONG";
    public static final String TYPE_DOUBLE = "DOUBLE";
    public static final String TYPE_DECIMAL = "DECIMAL";
    public static final String TYPE_STRING = "STRING";

    public static final Map<String, String> SCALAR_CONSUMABLE_TYPES = Map.ofEntries(
            Map.entry(DEVICE_NOVELTY, TYPE_BOOLEAN),
            Map.entry(COUNTRY_MISMATCH, TYPE_BOOLEAN),
            Map.entry(PROXY_OR_VPN_DETECTED, TYPE_BOOLEAN),
            Map.entry(RAPID_TRANSFER_BURST, TYPE_BOOLEAN),
            Map.entry(RECENT_TRANSACTION_COUNT, TYPE_INTEGER),
            Map.entry(RECENT_TRANSACTION_COUNT_WINDOW, TYPE_STRING),
            Map.entry(RECENT_AMOUNT_SUM_WINDOW, TYPE_STRING),
            Map.entry(MERCHANT_FREQUENCY_7D, TYPE_INTEGER),
            Map.entry(HIGH_RISK_FLAG_COUNT, TYPE_INTEGER),
            Map.entry(RECENT_AMOUNT_SUM, TYPE_DECIMAL),
            Map.entry(TRANSACTION_VELOCITY_PER_MINUTE, TYPE_DOUBLE),
            Map.entry(TRANSACTION_VELOCITY_PER_HOUR, TYPE_DOUBLE),
            Map.entry(TRANSACTION_VELOCITY_PER_DAY, TYPE_DOUBLE),
            Map.entry(RECENT_AMOUNT_AVERAGE, TYPE_DOUBLE),
            Map.entry(RECENT_AMOUNT_STD_DEV, TYPE_DOUBLE),
            Map.entry(AMOUNT_DEVIATION_FROM_USER_MEAN, TYPE_DOUBLE),
            Map.entry(MERCHANT_ENTROPY, TYPE_DOUBLE),
            Map.entry(COUNTRY_ENTROPY, TYPE_DOUBLE),
            Map.entry(RECENT_AMOUNT_SUM_PLN, TYPE_DECIMAL),
            Map.entry(CURRENT_TRANSACTION_AMOUNT_PLN, TYPE_DECIMAL),
            Map.entry(CUSTOMER_SEGMENT, TYPE_STRING),
            Map.entry(MERCHANT_CATEGORY, TYPE_STRING),
            Map.entry(CURRENCY, TYPE_STRING)
    );

    private FraudFeatureContract() {
    }

    public static Optional<String> expectedScalarTypeFor(String key) {
        return Optional.ofNullable(SCALAR_CONSUMABLE_TYPES.get(key));
    }
}
