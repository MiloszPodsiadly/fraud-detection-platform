package com.frauddetection.alert.mapper;

import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.persistence.AlertDocument;
import org.springframework.stereotype.Component;

@Component
public class AlertDocumentMapper {

    public AlertDocument toDocument(AlertCase alertCase) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertCase.alertId());
        document.setTransactionId(alertCase.transactionId());
        document.setCustomerId(alertCase.customerId());
        document.setCorrelationId(alertCase.correlationId());
        document.setCreatedAt(alertCase.createdAt());
        document.setAlertTimestamp(alertCase.alertTimestamp());
        document.setRiskLevel(alertCase.riskLevel());
        document.setFraudScore(alertCase.fraudScore());
        document.setAlertStatus(alertCase.alertStatus());
        document.setAlertReason(alertCase.alertReason());
        document.setReasonCodes(alertCase.reasonCodes());
        document.setTransactionAmount(alertCase.transactionAmount());
        document.setMerchantInfo(alertCase.merchantInfo());
        document.setDeviceInfo(alertCase.deviceInfo());
        document.setLocationInfo(alertCase.locationInfo());
        document.setCustomerContext(alertCase.customerContext());
        document.setScoreDetails(alertCase.scoreDetails());
        document.setFeatureSnapshot(alertCase.featureSnapshot());
        document.setAnalystDecision(alertCase.analystDecision());
        document.setAnalystId(alertCase.analystId());
        document.setDecisionReason(alertCase.decisionReason());
        document.setDecisionTags(alertCase.decisionTags());
        document.setDecidedAt(alertCase.decidedAt());
        return document;
    }

    public AlertCase toDomain(AlertDocument document) {
        return new AlertCase(
                document.getAlertId(),
                document.getTransactionId(),
                document.getCustomerId(),
                document.getCorrelationId(),
                document.getCreatedAt(),
                document.getAlertTimestamp(),
                document.getRiskLevel(),
                document.getFraudScore(),
                document.getAlertStatus(),
                document.getAlertReason(),
                document.getReasonCodes(),
                document.getTransactionAmount(),
                document.getMerchantInfo(),
                document.getDeviceInfo(),
                document.getLocationInfo(),
                document.getCustomerContext(),
                document.getScoreDetails(),
                document.getFeatureSnapshot(),
                document.getAnalystDecision(),
                document.getAnalystId(),
                document.getDecisionReason(),
                document.getDecisionTags(),
                document.getDecidedAt()
        );
    }
}
