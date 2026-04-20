package com.frauddetection.common.testsupport.fixture;

import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FraudAlertEventFixture {

    private FraudAlertEventFixture() {
    }

    public static FraudAlertEventBuilder builder() {
        return new FraudAlertEventBuilder();
    }

    public static final class FraudAlertEventBuilder {

        private String eventId = UUID.randomUUID().toString();
        private String alertId = "alert-1001";
        private String transactionId = "txn-1001";
        private String customerId = "cust-1001";
        private String correlationId = "corr-1001";
        private Instant createdAt = Instant.parse("2026-04-20T10:15:30Z");
        private Instant alertTimestamp = Instant.parse("2026-04-20T10:15:32Z");
        private RiskLevel riskLevel = RiskLevel.HIGH;
        private Double fraudScore = 0.94d;
        private AlertStatus alertStatus = AlertStatus.OPEN;
        private String alertReason = "High-risk transaction detected";
        private List<String> reasonCodes = List.of("HIGH_AMOUNT", "DEVICE_NOVELTY");
        private Map<String, Object> scoreDetails = Map.of("ruleScore", 0.94d);
        private Map<String, Object> featureSnapshot = Map.of("deviceNovelty", true, "recentTransactionCount", 8);

        public FraudAlertEventBuilder withAlertId(String alertId) {
            this.alertId = alertId;
            return this;
        }

        public FraudAlertEventBuilder withTransactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public FraudAlertEventBuilder withCustomerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public FraudAlertEventBuilder withAlertStatus(AlertStatus alertStatus) {
            this.alertStatus = alertStatus;
            return this;
        }

        public FraudAlertEvent build() {
            return new FraudAlertEvent(
                    eventId,
                    alertId,
                    transactionId,
                    customerId,
                    correlationId,
                    createdAt,
                    alertTimestamp,
                    riskLevel,
                    fraudScore,
                    alertStatus,
                    alertReason,
                    reasonCodes,
                    TransactionFixtures.defaultMoney(),
                    TransactionFixtures.defaultMerchantInfo(),
                    TransactionFixtures.defaultDeviceInfo(),
                    TransactionFixtures.defaultLocationInfo(),
                    TransactionFixtures.defaultCustomerContext(),
                    scoreDetails,
                    featureSnapshot
            );
        }
    }
}
