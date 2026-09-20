package com.frauddetection.scoring.service;

import com.frauddetection.common.events.features.FraudFeatureContract;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RulesV2ValidatedInput {
    private final int recentTransactionCount;
    private final String recentTransactionCountWindow;
    private final double transactionVelocityPerMinute;
    private final BigDecimal recentAmountSumPln;
    private final String recentAmountSumWindow;
    private final BigDecimal currentTransactionAmountPln;
    private final int merchantFrequency7d;
    private final boolean deviceNovelty;
    private final boolean countryMismatch;
    private final boolean proxyOrVpnDetected;
    private final String currency;
    private final Map<String, Object> canonicalFeatureSnapshot;

    RulesV2ValidatedInput(
            int recentTransactionCount,
            String recentTransactionCountWindow,
            double transactionVelocityPerMinute,
            BigDecimal recentAmountSumPln,
            String recentAmountSumWindow,
            BigDecimal currentTransactionAmountPln,
            int merchantFrequency7d,
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpnDetected,
            String currency
    ) {
        this.recentTransactionCount = recentTransactionCount;
        this.recentTransactionCountWindow = Objects.requireNonNull(
                recentTransactionCountWindow,
                "recentTransactionCountWindow is required"
        );
        this.transactionVelocityPerMinute = transactionVelocityPerMinute;
        this.recentAmountSumPln = Objects.requireNonNull(recentAmountSumPln, "recentAmountSumPln is required");
        this.recentAmountSumWindow = Objects.requireNonNull(recentAmountSumWindow, "recentAmountSumWindow is required");
        this.currentTransactionAmountPln = Objects.requireNonNull(
                currentTransactionAmountPln,
                "currentTransactionAmountPln is required"
        );
        this.merchantFrequency7d = merchantFrequency7d;
        this.deviceNovelty = deviceNovelty;
        this.countryMismatch = countryMismatch;
        this.proxyOrVpnDetected = proxyOrVpnDetected;
        this.currency = Objects.requireNonNull(currency, "currency is required");
        this.canonicalFeatureSnapshot = canonicalFeatureSnapshot();
    }

    public int recentTransactionCount() {
        return recentTransactionCount;
    }

    public String recentTransactionCountWindow() {
        return recentTransactionCountWindow;
    }

    public double transactionVelocityPerMinute() {
        return transactionVelocityPerMinute;
    }

    public BigDecimal recentAmountSumPln() {
        return recentAmountSumPln;
    }

    public String recentAmountSumWindow() {
        return recentAmountSumWindow;
    }

    public BigDecimal currentTransactionAmountPln() {
        return currentTransactionAmountPln;
    }

    public int merchantFrequency7d() {
        return merchantFrequency7d;
    }

    public boolean deviceNovelty() {
        return deviceNovelty;
    }

    public boolean countryMismatch() {
        return countryMismatch;
    }

    public boolean proxyOrVpnDetected() {
        return proxyOrVpnDetected;
    }

    public String currency() {
        return currency;
    }

    public Map<String, Object> canonicalFeatureSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, recentTransactionCount);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, recentTransactionCountWindow);
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, transactionVelocityPerMinute);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, recentAmountSumPln);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, recentAmountSumWindow);
        snapshot.put(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, currentTransactionAmountPln);
        snapshot.put(FraudFeatureContract.MERCHANT_FREQUENCY_7D, merchantFrequency7d);
        snapshot.put(FraudFeatureContract.DEVICE_NOVELTY, deviceNovelty);
        snapshot.put(FraudFeatureContract.COUNTRY_MISMATCH, countryMismatch);
        snapshot.put(FraudFeatureContract.PROXY_OR_VPN_DETECTED, proxyOrVpnDetected);
        snapshot.put(FraudFeatureContract.CURRENCY, currency);
        return Map.copyOf(snapshot);
    }

    public Map<String, Object> featureSnapshot() {
        return canonicalFeatureSnapshot;
    }
}
