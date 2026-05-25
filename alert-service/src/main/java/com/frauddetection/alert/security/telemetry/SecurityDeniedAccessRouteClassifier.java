package com.frauddetection.alert.security.telemetry;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SecurityDeniedAccessRouteClassifier {

    /**
     * Returns bounded route groups only. New route families require an explicit allowlisted routeGroup and tests.
     * Unknown routes must return unknown/internal_other, never the raw path.
     */
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
        if (isCurrentFraudCaseRoute(normalized)) {
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

    private boolean isCurrentFraudCaseRoute(String path) {
        String prefix = "/api/v1/fraud-cases/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        String suffix = path.substring(prefix.length());
        if (suffix.equals("work-queue") || suffix.equals("work-queue/summary")) {
            return true;
        }
        String[] segments = suffix.split("/");
        if (segments.length == 1) {
            return StringUtils.hasText(segments[0]);
        }
        return segments.length == 2
                && StringUtils.hasText(segments[0])
                && (segments[1].equals("evidence-summary") || segments[1].equals("evidence-timeline"));
    }
}
