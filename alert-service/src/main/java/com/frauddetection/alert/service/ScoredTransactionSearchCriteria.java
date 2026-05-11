package com.frauddetection.alert.service;

public record ScoredTransactionSearchCriteria(
        String query,
        String riskLevel,
        String classification
) {

    public boolean hasFilters() {
        return hasText(query) || isSelected(riskLevel) || isSelected(classification);
    }

    private static boolean isSelected(String value) {
        return hasText(value) && !"ALL".equalsIgnoreCase(value.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
