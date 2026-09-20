package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class TransactionFeatureCalculator {

    private final CurrencyAmountConverter currencyAmountConverter;

    public TransactionFeatureCalculator(CurrencyAmountConverter currencyAmountConverter) {
        this.currencyAmountConverter = Objects.requireNonNull(currencyAmountConverter, "currencyAmountConverter is required");
    }

    public EnrichedTransactionFeatures calculate(TransactionRawEvent event, FeatureStoreSnapshot snapshot) {
        int recentTransactionCount = snapshot.recentTransactionCount() + 1;
        BigDecimal currentAmountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(), event.transactionAmount().currency());
        BigDecimal recentAmountSumPln = snapshot.recentAmountSumPln().add(currentAmountPln);
        int merchantFrequency7d = snapshot.merchantFrequency7d() + 1;

        boolean knownFromContext = event.customerContext().knownDeviceIds() != null
                && event.customerContext().knownDeviceIds().contains(event.deviceInfo().deviceId());
        boolean deviceNovelty = !snapshot.knownDevice() && !knownFromContext;
        boolean countryMismatch = !event.locationInfo().countryCode().equalsIgnoreCase(event.customerContext().homeCountryCode());
        boolean proxyOrVpnDetected = Boolean.TRUE.equals(event.deviceInfo().proxyDetected())
                || Boolean.TRUE.equals(event.deviceInfo().vpnDetected());

        double velocityPerMinute = VelocityFeatureContract.expectedRatePerMinute(recentTransactionCount);

        Map<String, Object> featureSnapshot = new LinkedHashMap<>();
        String canonicalObservationWindow = VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT;
        featureSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, recentTransactionCount);
        featureSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, canonicalObservationWindow);
        featureSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, canonicalObservationWindow);
        featureSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, recentAmountSumPln);
        featureSnapshot.put(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, currentAmountPln);
        featureSnapshot.put(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, rapidTransferTransactionIds(snapshot, event));
        featureSnapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, velocityPerMinute);
        featureSnapshot.put(FraudFeatureContract.MERCHANT_FREQUENCY_7D, merchantFrequency7d);
        featureSnapshot.put(FraudFeatureContract.DEVICE_NOVELTY, deviceNovelty);
        featureSnapshot.put(FraudFeatureContract.COUNTRY_MISMATCH, countryMismatch);
        featureSnapshot.put(FraudFeatureContract.PROXY_OR_VPN_DETECTED, proxyOrVpnDetected);
        featureSnapshot.put(FraudFeatureContract.CUSTOMER_SEGMENT, event.customerContext().segment());
        featureSnapshot.put(FraudFeatureContract.MERCHANT_CATEGORY, event.merchantInfo().merchantCategory());
        featureSnapshot.put(FraudFeatureContract.CURRENCY, event.transactionAmount().currency().toUpperCase(Locale.ROOT));

        return new EnrichedTransactionFeatures(featureSnapshot);
    }

    private java.util.List<String> rapidTransferTransactionIds(FeatureStoreSnapshot snapshot, TransactionRawEvent event) {
        java.util.List<String> transactionIds = new ArrayList<>();
        snapshot.recentTransactions().forEach(transaction -> transactionIds.add(transaction.transactionId()));
        transactionIds.add(event.transactionId());
        return java.util.List.copyOf(transactionIds);
    }
}
