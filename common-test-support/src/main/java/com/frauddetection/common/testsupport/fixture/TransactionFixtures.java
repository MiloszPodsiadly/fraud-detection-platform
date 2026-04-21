package com.frauddetection.common.testsupport.fixture;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TransactionFixtures {

    private TransactionFixtures() {
    }

    public static TransactionRawEventBuilder rawTransaction() {
        return new TransactionRawEventBuilder();
    }

    public static TransactionEnrichedEventBuilder enrichedTransaction() {
        return new TransactionEnrichedEventBuilder();
    }

    public static TransactionScoredEventBuilder scoredTransaction() {
        return new TransactionScoredEventBuilder();
    }

    static Money defaultMoney() {
        return new Money(new BigDecimal("1249.99"), "USD");
    }

    static MerchantInfo defaultMerchantInfo() {
        return new MerchantInfo(
                "merchant-1001",
                "Northwind Electronics",
                "5732",
                "Electronics",
                "US",
                "ECOMMERCE",
                false,
                Map.of("merchantRiskTier", "medium")
        );
    }

    static DeviceInfo defaultDeviceInfo() {
        return new DeviceInfo(
                "device-1001",
                "fp-7ab921",
                "203.0.113.24",
                "Mozilla/5.0",
                "ANDROID",
                "CHROME",
                false,
                false,
                false,
                Map.of("emulatorDetected", false)
        );
    }

    static LocationInfo defaultLocationInfo() {
        return new LocationInfo(
                "US",
                "CA",
                "San Francisco",
                "94105",
                37.7897,
                -122.3942,
                "America/Los_Angeles",
                false
        );
    }

    static CustomerContext defaultCustomerContext() {
        return new CustomerContext(
                "cust-1001",
                "acct-1001",
                "PREMIUM",
                "example.com",
                620,
                true,
                true,
                "US",
                "USD",
                List.of("device-0901", "device-0902"),
                Map.of("kycLevel", "FULL")
        );
    }

    public static final class TransactionRawEventBuilder {

        private String eventId = UUID.randomUUID().toString();
        private String transactionId = "txn-1001";
        private String correlationId = "corr-1001";
        private String customerId = "cust-1001";
        private String accountId = "acct-1001";
        private String paymentInstrumentId = "card-1001";
        private Instant createdAt = Instant.parse("2026-04-20T10:15:30Z");
        private Instant transactionTimestamp = Instant.parse("2026-04-20T10:15:28Z");
        private Money transactionAmount = defaultMoney();

        public TransactionRawEventBuilder withTransactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public TransactionRawEventBuilder withCustomerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public TransactionRawEventBuilder withAmount(BigDecimal amount, String currency) {
            this.transactionAmount = new Money(amount, currency);
            return this;
        }

        public TransactionRawEvent build() {
            return new TransactionRawEvent(
                    eventId,
                    transactionId,
                    correlationId,
                    customerId,
                    accountId,
                    paymentInstrumentId,
                    createdAt,
                    transactionTimestamp,
                    transactionAmount,
                    defaultMerchantInfo(),
                    defaultDeviceInfo(),
                    defaultLocationInfo(),
                    defaultCustomerContext(),
                    "PURCHASE",
                    "3DS",
                    "PAYMENT_GATEWAY",
                    "trace-1001",
                    Map.of("channel", "mobile-app")
            );
        }
    }

    public static final class TransactionEnrichedEventBuilder {

        private String eventId = UUID.randomUUID().toString();
        private String transactionId = "txn-1001";
        private String correlationId = "corr-1001";
        private String customerId = "cust-1001";
        private String accountId = "acct-1001";
        private Instant createdAt = Instant.parse("2026-04-20T10:15:31Z");
        private Instant transactionTimestamp = Instant.parse("2026-04-20T10:15:28Z");

        public TransactionEnrichedEventBuilder withTransactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public TransactionEnrichedEvent build() {
            return new TransactionEnrichedEvent(
                    eventId,
                    transactionId,
                    correlationId,
                    customerId,
                    accountId,
                    createdAt,
                    transactionTimestamp,
                    defaultMoney(),
                    defaultMerchantInfo(),
                    defaultDeviceInfo(),
                    defaultLocationInfo(),
                    defaultCustomerContext(),
                    8,
                    "PT1M",
                    new Money(new BigDecimal("5830.24"), "USD"),
                    "PT24H",
                    1.9d,
                    6,
                    true,
                    false,
                    false,
                    List.of("DEVICE_NOVELTY", "HIGH_VELOCITY"),
                    Map.of(
                            "recentTransactionCount", 8,
                            "recentAmountSum", "5830.24",
                            "deviceNovelty", true,
                            "merchantFrequency7d", 6
                    )
            );
        }
    }

    public static final class TransactionScoredEventBuilder {

        private String eventId = UUID.randomUUID().toString();
        private String transactionId = "txn-1001";
        private String correlationId = "corr-1001";
        private String customerId = "cust-1001";
        private String accountId = "acct-1001";
        private Instant createdAt = Instant.parse("2026-04-20T10:15:33Z");
        private Instant transactionTimestamp = Instant.parse("2026-04-20T10:15:28Z");
        private Money transactionAmount = defaultMoney();
        private Double fraudScore = 0.94d;
        private RiskLevel riskLevel = RiskLevel.HIGH;
        private Map<String, Object> featureSnapshot = Map.of("recentTransactionCount", 8, "deviceNovelty", true);

        public TransactionScoredEventBuilder withTransactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public TransactionScoredEventBuilder withCustomerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public TransactionScoredEventBuilder withAmount(BigDecimal amount, String currency) {
            this.transactionAmount = new Money(amount, currency);
            return this;
        }

        public TransactionScoredEventBuilder withFraudScore(Double fraudScore) {
            this.fraudScore = fraudScore;
            return this;
        }

        public TransactionScoredEventBuilder withRiskLevel(RiskLevel riskLevel) {
            this.riskLevel = riskLevel;
            return this;
        }

        public TransactionScoredEventBuilder withFeatureSnapshot(Map<String, Object> featureSnapshot) {
            this.featureSnapshot = featureSnapshot;
            return this;
        }

        public TransactionScoredEvent build() {
            return new TransactionScoredEvent(
                    eventId,
                    transactionId,
                    correlationId,
                    customerId,
                    accountId,
                    createdAt,
                    transactionTimestamp,
                    transactionAmount,
                    defaultMerchantInfo(),
                    defaultDeviceInfo(),
                    defaultLocationInfo(),
                    defaultCustomerContext(),
                    fraudScore,
                    riskLevel,
                    "RULE_BASED",
                    "rule-engine",
                    "v1",
                    Instant.parse("2026-04-20T10:15:33Z"),
                    List.of("HIGH_AMOUNT", "DEVICE_NOVELTY", "HIGH_VELOCITY"),
                    Map.of("baseScore", 0.72d, "velocityBoost", 0.12d, "deviceBoost", 0.10d),
                    featureSnapshot,
                    true
            );
        }
    }
}
