package com.frauddetection.alert.service;

import com.frauddetection.common.events.enums.RiskLevel;

public record ScoredTransactionSearchCriteria(
        String query,
        RiskLevel riskLevel,
        Boolean alertRecommended
) {
    public boolean hasFilters() {
        return hasText(query) || riskLevel != null || alertRecommended != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
