package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.AlertDetailsResponse;
import com.frauddetection.alert.api.AlertSummaryResponse;
import com.frauddetection.alert.domain.AlertCase;
import org.springframework.stereotype.Component;

@Component
public class AlertResponseMapper {

    public AlertSummaryResponse toSummary(AlertCase alertCase) {
        return new AlertSummaryResponse(
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.riskLevel(),
                alertCase.fraudScore(),
                alertCase.alertStatus(),
                alertCase.alertReason(),
                alertCase.alertTimestamp()
        );
    }

    public AlertDetailsResponse toDetails(AlertCase alertCase) {
        return new AlertDetailsResponse(
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.correlationId(),
                alertCase.createdAt(),
                alertCase.alertTimestamp(),
                alertCase.riskLevel(),
                alertCase.fraudScore(),
                alertCase.alertStatus(),
                alertCase.alertReason(),
                alertCase.reasonCodes(),
                alertCase.transactionAmount(),
                alertCase.merchantInfo(),
                alertCase.deviceInfo(),
                alertCase.locationInfo(),
                alertCase.customerContext(),
                alertCase.scoreDetails(),
                alertCase.featureSnapshot(),
                alertCase.analystDecision(),
                alertCase.analystId(),
                alertCase.decisionReason(),
                alertCase.decisionTags(),
                alertCase.decidedAt()
        );
    }
}
