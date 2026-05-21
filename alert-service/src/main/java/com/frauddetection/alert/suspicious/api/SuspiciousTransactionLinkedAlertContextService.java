package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class SuspiciousTransactionLinkedAlertContextService {

    private final SuspiciousTransactionRepository suspiciousTransactionRepository;
    private final AlertRepository alertRepository;

    public SuspiciousTransactionLinkedAlertContextService(
            SuspiciousTransactionRepository suspiciousTransactionRepository,
            AlertRepository alertRepository
    ) {
        this.suspiciousTransactionRepository = Objects.requireNonNull(
                suspiciousTransactionRepository,
                "suspiciousTransactionRepository is required"
        );
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository is required");
    }

    public AlertLinkedContextResponse resolveLinkedAlertContext(String suspiciousTransactionId) {
        String normalizedSuspiciousTransactionId = normalize(suspiciousTransactionId);
        if (normalizedSuspiciousTransactionId == null) {
            throw new SuspiciousTransactionLinkedAlertContextNotFoundException();
        }

        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransactionRepository
                .findById(normalizedSuspiciousTransactionId)
                .orElseThrow(SuspiciousTransactionLinkedAlertContextNotFoundException::new);

        String linkedAlertId = normalize(suspiciousTransaction.getLinkedAlertId());
        if (linkedAlertId == null) {
            return AlertLinkedContextResponse.noLinkedAlert();
        }

        return alertRepository.findById(linkedAlertId)
                .map(alert -> responseForLinkedAlert(suspiciousTransaction, alert, linkedAlertId))
                .orElseGet(AlertLinkedContextResponse::linkedAlertNotFound);
    }

    private AlertLinkedContextResponse responseForLinkedAlert(
            SuspiciousTransactionDocument suspiciousTransaction,
            AlertDocument alert,
            String linkedAlertId
    ) {
        if (!matches(linkedAlertId, alert.getAlertId())
                || conflicts(suspiciousTransaction.getTransactionId(), alert.getTransactionId())
                || conflicts(suspiciousTransaction.getCustomerId(), alert.getCustomerId())
                || conflicts(suspiciousTransaction.getCorrelationId(), alert.getCorrelationId())) {
            return AlertLinkedContextResponse.relationshipMismatch();
        }

        return AlertLinkedContextResponse.available(
                alert.getAlertId(),
                alert.getTransactionId(),
                alert.getCustomerId(),
                alert.getCustomerContext() == null ? suspiciousTransaction.getAccountId() : alert.getCustomerContext().accountId(),
                alert.getFraudScore(),
                alert.getRiskLevel(),
                alert.getAlertStatus(),
                alert.getReasonCodes(),
                alert.getCreatedAt(),
                null,
                alert.getCorrelationId(),
                suspiciousTransaction.getScoreDecisionId()
        );
    }

    private boolean conflicts(String expected, String actual) {
        String normalizedExpected = normalize(expected);
        String normalizedActual = normalize(actual);
        return normalizedExpected != null && normalizedActual != null && !normalizedExpected.equals(normalizedActual);
    }

    private boolean matches(String expected, String actual) {
        String normalizedExpected = normalize(expected);
        String normalizedActual = normalize(actual);
        return normalizedExpected != null && normalizedExpected.equals(normalizedActual);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
