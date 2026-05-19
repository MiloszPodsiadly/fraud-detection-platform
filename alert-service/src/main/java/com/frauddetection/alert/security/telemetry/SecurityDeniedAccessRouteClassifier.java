package com.frauddetection.alert.security.telemetry;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SecurityDeniedAccessRouteClassifier {

    public String classify(String path) {
        if (!StringUtils.hasText(path)) {
            return "unknown";
        }
        String normalized = stripQuery(path.trim());
        if (normalized.equals("/internal/suspicious-transactions")
                || normalized.startsWith("/internal/suspicious-transactions/")) {
            return "suspicious_transaction_read";
        }
        if (normalized.equals("/api/v1/alerts")
                || normalized.startsWith("/api/v1/alerts/")) {
            return "fraud_alert";
        }
        if (normalized.equals("/api/v1/fraud-cases")
                || normalized.startsWith("/api/v1/fraud-cases/")
                || normalized.equals("/api/fraud-cases")
                || normalized.startsWith("/api/fraud-cases/")) {
            return "fraud_case";
        }
        if (normalized.equals("/system/trust-level")
                || normalized.startsWith("/api/v1/trust/")
                || normalized.startsWith("/api/v1/audit/trust/")) {
            return "trust";
        }
        if (normalized.equals("/internal") || normalized.startsWith("/internal/")) {
            return "internal_other";
        }
        return "unknown";
    }

    private String stripQuery(String path) {
        int queryStart = path.indexOf('?');
        return queryStart < 0 ? path : path.substring(0, queryStart);
    }
}
