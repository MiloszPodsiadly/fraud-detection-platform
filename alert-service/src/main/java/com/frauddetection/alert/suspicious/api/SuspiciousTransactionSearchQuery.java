package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SuspiciousTransactionSearchQuery(
        SuspiciousTransactionStatus status,
        RiskLevel riskLevel,
        String customerId,
        String linkedAlertId,
        Instant detectedFrom,
        Instant detectedTo,
        int size,
        String cursor
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String SORT_FIELD = "detectedAt";
    public static final String TIE_BREAKER_SORT_FIELD = "suspiciousTransactionId";
    public static final String SORT_DIRECTION = "DESC";

    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "status",
            "riskLevel",
            "customerId",
            "linkedAlertId",
            "detectedFrom",
            "detectedTo",
            "size",
            "cursor"
    );

    public SuspiciousTransactionSearchQuery {
        customerId = normalizeText(customerId);
        linkedAlertId = normalizeText(linkedAlertId);
        cursor = normalizeCursor(cursor);
        validateSize(size);
        validateDetectedRange(detectedFrom, detectedTo);
    }

    public static SuspiciousTransactionSearchQuery from(MultiValueMap<String, String> parameters) {
        validateParameters(parameters);
        return new SuspiciousTransactionSearchQuery(
                enumValue(value(parameters, "status"), SuspiciousTransactionStatus.class),
                enumValue(value(parameters, "riskLevel"), RiskLevel.class),
                requiredNonBlankIfPresent(parameters, "customerId"),
                requiredNonBlankIfPresent(parameters, "linkedAlertId"),
                instantValue(value(parameters, "detectedFrom")),
                instantValue(value(parameters, "detectedTo")),
                intValue(value(parameters, "size"), DEFAULT_SIZE),
                value(parameters, "cursor")
        );
    }

    public String sortField() {
        return SORT_FIELD;
    }

    public String sortDirection() {
        return SORT_DIRECTION;
    }

    public boolean hasStatusFilter() {
        return status != null;
    }

    public boolean hasRiskLevelFilter() {
        return riskLevel != null;
    }

    public boolean hasCustomerFilter() {
        return customerId != null;
    }

    public boolean hasLinkedAlertFilter() {
        return linkedAlertId != null;
    }

    public boolean hasDetectedRange() {
        return detectedFrom != null || detectedTo != null;
    }

    public static boolean isAllowedSortField(String field) {
        return SORT_FIELD.equals(field);
    }

    private static void validateParameters(MultiValueMap<String, String> parameters) {
        if (parameters == null) {
            return;
        }
        for (String name : parameters.keySet()) {
            if (!ALLOWED_PARAMETERS.contains(name)) {
                throw invalid();
            }
            if (parameters.getOrDefault(name, List.of()).size() > 1) {
                throw invalid();
            }
        }
    }

    private static String value(MultiValueMap<String, String> parameters, String name) {
        if (parameters == null || !parameters.containsKey(name) || parameters.get(name).isEmpty()) {
            return null;
        }
        return parameters.getFirst(name);
    }

    private static String requiredNonBlankIfPresent(MultiValueMap<String, String> parameters, String name) {
        if (parameters == null || !parameters.containsKey(name)) {
            return null;
        }
        String value = parameters.getFirst(name);
        if (!StringUtils.hasText(value)) {
            throw invalid();
        }
        return value.trim();
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> enumType) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static Instant instantValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static int intValue(String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw invalid();
        }
    }

    private static void validateDetectedRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid();
        }
    }

    private static String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String normalizeCursor(String value) {
        if (value == null) {
            return null;
        }
        if (!StringUtils.hasText(value)) {
            throw invalid();
        }
        return value.trim();
    }

    private static SuspiciousTransactionReadValidationException invalid() {
        return new SuspiciousTransactionReadValidationException("INVALID_SUSPICIOUS_TRANSACTION_QUERY");
    }
}
