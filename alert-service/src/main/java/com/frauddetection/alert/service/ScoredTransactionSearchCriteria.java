package com.frauddetection.alert.service;

public record ScoredTransactionSearchCriteria(
        String query,
        String riskLevel,
        String classification
) {
    public static final int MIN_QUERY_LENGTH = 3;
    public static final int MAX_QUERY_LENGTH = 128;

    public ScoredTransactionSearchCriteria {
        query = normalizeQuery(query);
        riskLevel = normalizeSelection(riskLevel);
        classification = normalizeSelection(classification);
    }

    public boolean hasFilters() {
        return hasText(query) || isSelected(riskLevel) || isSelected(classification);
    }

    private static String normalizeQuery(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Scored transaction query is too long.");
        }
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private static String normalizeSelection(String value) {
        if (!hasText(value)) {
            return "ALL";
        }
        return value.trim();
    }

    private static boolean isSelected(String value) {
        return hasText(value) && !"ALL".equalsIgnoreCase(value.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
