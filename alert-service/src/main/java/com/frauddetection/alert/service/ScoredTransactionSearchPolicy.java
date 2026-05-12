package com.frauddetection.alert.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ScoredTransactionSearchPolicy {

    public static final int MIN_QUERY_LENGTH = 3;
    public static final int MAX_QUERY_LENGTH = 128;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_NUMBER = 1000;
    public static final int MAX_FILTERED_TOTAL_COUNT = 10_000;

    public ScoredTransactionSearchCriteria criteria(String query, String riskLevel, String classification) {
        return new ScoredTransactionSearchCriteria(
                normalizeQuery(query),
                normalizeSelection(riskLevel),
                normalizeSelection(classification)
        );
    }

    public void validatePageAndSize(int page, int size) {
        if (page < 0 || page > MAX_PAGE_NUMBER) {
            throw invalid();
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid();
        }
    }

    public String filterBucket(ScoredTransactionSearchCriteria criteria) {
        if (criteria == null || !criteria.hasFilters()) {
            return "none";
        }
        boolean query = StringUtils.hasText(criteria.query());
        boolean risk = isSelected(criteria.riskLevel());
        boolean classification = isSelected(criteria.classification());
        int selected = (query ? 1 : 0) + (risk ? 1 : 0) + (classification ? 1 : 0);
        if (selected > 1) {
            return "combined";
        }
        if (query) {
            return "query";
        }
        if (risk) {
            return "risk";
        }
        if (classification) {
            return "classification";
        }
        return "none";
    }

    public String filterBucket(String query, String riskLevel, String classification) {
        boolean querySelected = StringUtils.hasText(query);
        boolean riskSelected = isSelected(riskLevel);
        boolean classificationSelected = isSelected(classification);
        int selected = (querySelected ? 1 : 0) + (riskSelected ? 1 : 0) + (classificationSelected ? 1 : 0);
        if (selected > 1) {
            return "combined";
        }
        if (querySelected) {
            return "query";
        }
        if (riskSelected) {
            return "risk";
        }
        if (classificationSelected) {
            return "classification";
        }
        return "none";
    }

    public static String canonicalAuditQuery(Map<String, String[]> parameterMap) {
        if (parameterMap == null || parameterMap.isEmpty()) {
            return null;
        }
        TreeMap<String, String[]> sorted = new TreeMap<>();
        parameterMap.forEach((name, values) -> {
            if (name == null || name.isBlank() || values == null) {
                return;
            }
            String[] normalizedValues = Arrays.stream(values)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(value -> normalizeAuditValue(name, value))
                    .filter(StringUtils::hasText)
                    .sorted()
                    .toArray(String[]::new);
            if (normalizedValues.length > 0) {
                sorted.put(name.trim(), normalizedValues);
            }
        });
        if (sorted.isEmpty()) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((name, values) -> Arrays.stream(values).forEach(value ->
                canonical.append(name).append('=').append(value).append('\n')
        ));
        return canonical.toString();
    }

    private static String normalizeAuditValue(String name, String value) {
        if ("query".equals(name)) {
            String trimmed = value.trim();
            if (trimmed.isBlank()) {
                return null;
            }
            if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH) {
                return "invalid_length";
            }
            return trimmed.toLowerCase();
        }
        if ("riskLevel".equals(name) || "classification".equals(name)) {
            return value.trim().toUpperCase();
        }
        if ("page".equals(name) || "size".equals(name)) {
            return value.trim();
        }
        return "present";
    }

    private String normalizeQuery(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH) {
            throw invalid();
        }
        return trimmed;
    }

    private String normalizeSelection(String value) {
        if (!StringUtils.hasText(value)) {
            return "ALL";
        }
        return value.trim();
    }

    private boolean isSelected(String value) {
        return StringUtils.hasText(value) && !"ALL".equalsIgnoreCase(value.trim());
    }

    private ScoredTransactionSearchValidationException invalid() {
        return new ScoredTransactionSearchValidationException("INVALID_FILTER");
    }
}
