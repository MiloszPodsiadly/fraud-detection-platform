package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class EvidenceProjectionTestSupport {

    static final Instant CREATED_AT = Instant.parse("2026-05-18T10:00:00Z");
    static final Instant INFERENCE_AT = Instant.parse("2026-05-18T10:00:05Z");

    private EvidenceProjectionTestSupport() {
    }

    static TransactionScoredEvent scoredEvent(RiskLevel riskLevel, List<String> reasonCodes) {
        return scoredEvent("event-1", "txn-1", "corr-1", riskLevel, reasonCodes);
    }

    static TransactionScoredEvent scoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            RiskLevel riskLevel,
            List<String> reasonCodes
    ) {
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                correlationId,
                "customer-1",
                "account-1",
                CREATED_AT,
                Instant.parse("2026-05-18T09:59:00Z"),
                null,
                null,
                null,
                null,
                null,
                0.91d,
                riskLevel,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                INFERENCE_AT,
                reasonCodes,
                Map.of("reasonCodeCount", reasonCodes == null ? 0 : reasonCodes.size()),
                Map.of(),
                true
        );
    }
}
