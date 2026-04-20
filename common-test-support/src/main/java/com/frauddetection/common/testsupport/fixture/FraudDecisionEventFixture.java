package com.frauddetection.common.testsupport.fixture;

import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FraudDecisionEventFixture {

    private FraudDecisionEventFixture() {
    }

    public static FraudDecisionEventBuilder builder() {
        return new FraudDecisionEventBuilder();
    }

    public static final class FraudDecisionEventBuilder {

        private String eventId = UUID.randomUUID().toString();
        private String decisionId = "decision-1001";
        private String alertId = "alert-1001";
        private String transactionId = "txn-1001";
        private String customerId = "cust-1001";
        private String correlationId = "corr-1001";
        private String analystId = "analyst-42";
        private AnalystDecision decision = AnalystDecision.CONFIRMED_FRAUD;
        private AlertStatus resultingStatus = AlertStatus.RESOLVED;
        private String decisionReason = "Transaction pattern is confirmed as fraudulent";
        private List<String> tags = List.of("chargeback-risk", "manual-review");
        private Map<String, Object> decisionMetadata = Map.of("casePriority", "high");
        private Instant createdAt = Instant.parse("2026-04-20T10:20:00Z");
        private Instant decidedAt = Instant.parse("2026-04-20T10:22:00Z");

        public FraudDecisionEventBuilder withDecision(AnalystDecision decision) {
            this.decision = decision;
            return this;
        }

        public FraudDecisionEventBuilder withResultingStatus(AlertStatus resultingStatus) {
            this.resultingStatus = resultingStatus;
            return this;
        }

        public FraudDecisionEvent build() {
            return new FraudDecisionEvent(
                    eventId,
                    decisionId,
                    alertId,
                    transactionId,
                    customerId,
                    correlationId,
                    analystId,
                    decision,
                    resultingStatus,
                    decisionReason,
                    tags,
                    decisionMetadata,
                    createdAt,
                    decidedAt
            );
        }
    }
}
