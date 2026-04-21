package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TransactionFeatureCalculator {

    private static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = BigDecimal.valueOf(20_000);
    private static final int HIGH_VELOCITY_TRANSACTION_COUNT = 5;

    private final FeatureStoreProperties featureStoreProperties;
    private final CurrencyAmountConverter currencyAmountConverter;

    public TransactionFeatureCalculator(FeatureStoreProperties featureStoreProperties, CurrencyAmountConverter currencyAmountConverter) {
        this.featureStoreProperties = featureStoreProperties;
        this.currencyAmountConverter = currencyAmountConverter;
    }

    public EnrichedTransactionFeatures calculate(TransactionRawEvent event, FeatureStoreSnapshot snapshot) {
        int recentTransactionCount = snapshot.recentTransactionCount() + 1;
        BigDecimal recentAmountSum = snapshot.recentAmountSum().add(event.transactionAmount().amount());
        BigDecimal currentAmountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(), event.transactionAmount().currency());
        BigDecimal recentAmountSumPln = snapshot.recentAmountSumPln().add(currentAmountPln);
        int merchantFrequency7d = snapshot.merchantFrequency7d() + 1;

        boolean knownFromContext = event.customerContext().knownDeviceIds() != null
                && event.customerContext().knownDeviceIds().contains(event.deviceInfo().deviceId());
        boolean deviceNovelty = !snapshot.knownDevice() && !knownFromContext;
        boolean countryMismatch = !event.locationInfo().countryCode().equalsIgnoreCase(event.customerContext().homeCountryCode());
        boolean proxyOrVpnDetected = Boolean.TRUE.equals(event.deviceInfo().proxyDetected())
                || Boolean.TRUE.equals(event.deviceInfo().vpnDetected());

        double velocityPerMinute = BigDecimal.valueOf(recentTransactionCount)
                .divide(BigDecimal.valueOf(Math.max(featureStoreProperties.recentTransactionWindow().toMinutes(), 1L)), 4, RoundingMode.HALF_UP)
                .doubleValue();

        List<String> featureFlags = new ArrayList<>();
        if (deviceNovelty) {
            featureFlags.add("DEVICE_NOVELTY");
        }
        if (countryMismatch) {
            featureFlags.add("COUNTRY_MISMATCH");
        }
        if (proxyOrVpnDetected) {
            featureFlags.add("PROXY_OR_VPN");
        }
        if (recentTransactionCount >= HIGH_VELOCITY_TRANSACTION_COUNT) {
            featureFlags.add("HIGH_VELOCITY");
        }
        if (merchantFrequency7d >= 5) {
            featureFlags.add("MERCHANT_CONCENTRATION");
        }
        if (recentTransactionCount >= 2 && recentAmountSum.compareTo(BigDecimal.valueOf(5000)) >= 0) {
            featureFlags.add("HIGH_AMOUNT_ACTIVITY");
        }
        boolean rapidPln20kBurst = recentTransactionCount >= 2 && recentAmountSumPln.compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
        if (rapidPln20kBurst) {
            featureFlags.add("RAPID_PLN_20K_BURST");
        }

        Map<String, Object> featureSnapshot = new LinkedHashMap<>();
        featureSnapshot.put("recentTransactionCount", recentTransactionCount);
        featureSnapshot.put("recentTransactionCountWindow", featureStoreProperties.recentTransactionWindow().toString());
        featureSnapshot.put("recentAmountSum", recentAmountSum);
        featureSnapshot.put("recentAmountSumWindow", featureStoreProperties.recentTransactionWindow().toString());
        featureSnapshot.put("recentAmountSumPln", recentAmountSumPln);
        featureSnapshot.put("currentTransactionAmountPln", currentAmountPln);
        featureSnapshot.put("rapidTransferWindow", featureStoreProperties.recentTransactionWindow().toString());
        featureSnapshot.put("rapidTransferThresholdPln", RAPID_TRANSFER_PLN_THRESHOLD);
        featureSnapshot.put("rapidTransferFraudCaseCandidate", rapidPln20kBurst);
        featureSnapshot.put("rapidTransferCount", recentTransactionCount);
        featureSnapshot.put("rapidTransferTotalPln", recentAmountSumPln);
        featureSnapshot.put("rapidTransferTransactionIds", rapidTransferTransactionIds(snapshot, event));
        featureSnapshot.put("transactionVelocityPerMinute", velocityPerMinute);
        featureSnapshot.put("merchantFrequency7d", merchantFrequency7d);
        featureSnapshot.put("deviceNovelty", deviceNovelty);
        featureSnapshot.put("countryMismatch", countryMismatch);
        featureSnapshot.put("proxyOrVpnDetected", proxyOrVpnDetected);
        featureSnapshot.put("customerSegment", event.customerContext().segment());
        featureSnapshot.put("merchantCategory", event.merchantInfo().merchantCategory());
        featureSnapshot.put("currency", event.transactionAmount().currency().toUpperCase(Locale.ROOT));
        featureSnapshot.put("featureFlags", List.copyOf(featureFlags));

        return new EnrichedTransactionFeatures(
                recentTransactionCount,
                featureStoreProperties.recentTransactionWindow().toString(),
                new Money(recentAmountSum, event.transactionAmount().currency()),
                featureStoreProperties.recentTransactionWindow().toString(),
                velocityPerMinute,
                merchantFrequency7d,
                deviceNovelty,
                countryMismatch,
                proxyOrVpnDetected,
                List.copyOf(featureFlags),
                featureSnapshot
        );
    }

    private List<String> rapidTransferTransactionIds(FeatureStoreSnapshot snapshot, TransactionRawEvent event) {
        List<String> transactionIds = new ArrayList<>();
        snapshot.recentTransactions().forEach(transaction -> transactionIds.add(transaction.transactionId()));
        transactionIds.add(event.transactionId());
        return List.copyOf(transactionIds);
    }
}
