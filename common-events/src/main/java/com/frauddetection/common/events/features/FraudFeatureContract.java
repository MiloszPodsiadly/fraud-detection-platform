package com.frauddetection.common.events.features;

import java.util.List;

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
    public static final String RAPID_TRANSFER_WINDOW = "rapidTransferWindow";
    public static final String RAPID_TRANSFER_THRESHOLD_PLN = "rapidTransferThresholdPln";
    public static final String RAPID_TRANSFER_FRAUD_CASE_CANDIDATE = "rapidTransferFraudCaseCandidate";
    public static final String RAPID_TRANSFER_COUNT = "rapidTransferCount";
    public static final String RAPID_TRANSFER_TOTAL_PLN = "rapidTransferTotalPln";
    public static final String RAPID_TRANSFER_TRANSACTION_IDS = "rapidTransferTransactionIds";
    public static final String CUSTOMER_SEGMENT = "customerSegment";
    public static final String MERCHANT_CATEGORY = "merchantCategory";
    public static final String CURRENCY = "currency";
    public static final String FEATURE_FLAGS = "featureFlags";

    public static final String FLAG_DEVICE_NOVELTY = "DEVICE_NOVELTY";
    public static final String FLAG_COUNTRY_MISMATCH = "COUNTRY_MISMATCH";
    public static final String FLAG_PROXY_OR_VPN = "PROXY_OR_VPN";
    public static final String FLAG_HIGH_VELOCITY = "HIGH_VELOCITY";
    public static final String FLAG_MERCHANT_CONCENTRATION = "MERCHANT_CONCENTRATION";
    public static final String FLAG_HIGH_AMOUNT_ACTIVITY = "HIGH_AMOUNT_ACTIVITY";
    public static final String FLAG_RAPID_PLN_20K_BURST = "RAPID_PLN_20K_BURST";

    public static final List<String> ML_FEATURE_NAMES = List.of(
            RECENT_TRANSACTION_COUNT,
            RECENT_AMOUNT_SUM,
            TRANSACTION_VELOCITY_PER_MINUTE,
            TRANSACTION_VELOCITY_PER_HOUR,
            TRANSACTION_VELOCITY_PER_DAY,
            RECENT_AMOUNT_AVERAGE,
            RECENT_AMOUNT_STD_DEV,
            AMOUNT_DEVIATION_FROM_USER_MEAN,
            MERCHANT_ENTROPY,
            COUNTRY_ENTROPY,
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
            RAPID_TRANSFER_WINDOW,
            RAPID_TRANSFER_THRESHOLD_PLN,
            RAPID_TRANSFER_FRAUD_CASE_CANDIDATE,
            RAPID_TRANSFER_COUNT,
            RAPID_TRANSFER_TOTAL_PLN,
            RAPID_TRANSFER_TRANSACTION_IDS,
            TRANSACTION_VELOCITY_PER_MINUTE,
            MERCHANT_FREQUENCY_7D,
            DEVICE_NOVELTY,
            COUNTRY_MISMATCH,
            PROXY_OR_VPN_DETECTED,
            CUSTOMER_SEGMENT,
            MERCHANT_CATEGORY,
            CURRENCY,
            FEATURE_FLAGS
    );

    public static final List<String> FEATURE_FLAGS_VALUES = List.of(
            FLAG_DEVICE_NOVELTY,
            FLAG_COUNTRY_MISMATCH,
            FLAG_PROXY_OR_VPN,
            FLAG_HIGH_VELOCITY,
            FLAG_MERCHANT_CONCENTRATION,
            FLAG_HIGH_AMOUNT_ACTIVITY,
            FLAG_RAPID_PLN_20K_BURST
    );

    private FraudFeatureContract() {
    }
}
