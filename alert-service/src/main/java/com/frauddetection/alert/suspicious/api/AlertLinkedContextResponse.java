package com.frauddetection.alert.suspicious.api;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

public record AlertLinkedContextResponse(
        LinkedAlertContextState state,
        String alertId,
        String transactionId,
        String customerId,
        String accountId,
        Double alertScore,
        RiskLevel riskLevel,
        AlertStatus alertStatus,
        List<String> reasonCodes,
        Instant createdAt,
        Instant updatedAt,
        String correlationId,
        String scoreDecisionId
) {

    public AlertLinkedContextResponse {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    static AlertLinkedContextResponse available(
            String alertId,
            String transactionId,
            String customerId,
            String accountId,
            Double alertScore,
            RiskLevel riskLevel,
            AlertStatus alertStatus,
            List<String> reasonCodes,
            Instant createdAt,
            Instant updatedAt,
            String correlationId,
            String scoreDecisionId
    ) {
        return new AlertLinkedContextResponse(
                LinkedAlertContextState.LINKED_ALERT_AVAILABLE,
                alertId,
                transactionId,
                customerId,
                accountId,
                alertScore,
                riskLevel,
                alertStatus,
                reasonCodes,
                createdAt,
                updatedAt,
                correlationId,
                scoreDecisionId
        );
    }

    static AlertLinkedContextResponse noLinkedAlert() {
        return unavailable(LinkedAlertContextState.NO_LINKED_ALERT);
    }

    static AlertLinkedContextResponse linkedAlertNotFound() {
        return unavailable(LinkedAlertContextState.LINKED_ALERT_NOT_FOUND);
    }

    static AlertLinkedContextResponse relationshipMismatch() {
        return unavailable(LinkedAlertContextState.LINKED_ALERT_RELATIONSHIP_MISMATCH);
    }

    static AlertLinkedContextResponse temporarilyUnavailable() {
        return unavailable(LinkedAlertContextState.TEMPORARILY_UNAVAILABLE);
    }

    private static AlertLinkedContextResponse unavailable(LinkedAlertContextState state) {
        return new AlertLinkedContextResponse(
                state,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null
        );
    }
}
