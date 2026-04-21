package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class AlertCaseFactory {

    public AlertCase from(TransactionScoredEvent event) {
        Instant now = Instant.now();

        return new AlertCase(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.customerId(),
                event.correlationId(),
                now,
                now,
                event.riskLevel(),
                event.fraudScore(),
                AlertStatus.OPEN,
                buildAlertReason(event),
                event.reasonCodes(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                event.scoreDetails(),
                event.featureSnapshot(),
                null,
                null,
                null,
                List.of(),
                null
        );
    }

    private String buildAlertReason(TransactionScoredEvent event) {
        if (event.reasonCodes() == null || event.reasonCodes().isEmpty()) {
            return "High-risk transaction requires analyst review.";
        }
        return "High-risk transaction flagged for review: " + String.join(", ", event.reasonCodes());
    }
}
