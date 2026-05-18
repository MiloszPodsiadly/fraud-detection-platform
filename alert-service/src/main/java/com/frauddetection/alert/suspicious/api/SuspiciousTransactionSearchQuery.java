package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        int page,
        int size,
        String sort
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "detectedAt,desc";

    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "status",
            "riskLevel",
            "customerId",
            "linkedAlertId",
            "detectedFrom",
            "detectedTo",
            "page",
            "size",
            "sort"
    );
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "detectedAt",
            "updatedAt",
            "riskScore",
            "riskLevel",
            "status"
    );
    private static final Set<String> FORBIDDEN_SORT_FIELDS = Set.of(
            "customerId",
            "accountId",
            "transactionId",
            "sourceEventId",
            "correlationId",
            "suspiciousTransactionId",
            "linkedAlertId"
    );

    public SuspiciousTransactionSearchQuery {
        customerId = normalizeText(customerId);
        linkedAlertId = normalizeText(linkedAlertId);
        sort = StringUtils.hasText(sort) ? sort.trim() : DEFAULT_SORT;
        validatePageAndSize(page, size);
        validateDetectedRange(detectedFrom, detectedTo);
        validateSort(sort);
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
                intValue(value(parameters, "page"), DEFAULT_PAGE),
                intValue(value(parameters, "size"), DEFAULT_SIZE),
                value(parameters, "sort")
        );
    }

    public Pageable pageable() {
        Sort.Direction direction = sortDirection(sort);
        return PageRequest.of(page, size, Sort.by(direction, sortField(sort)));
    }

    public String sortField() {
        return sortField(sort);
    }

    public String sortDirection() {
        return sortDirection(sort).name();
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
        return ALLOWED_SORT_FIELDS.contains(field);
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

    private static void validatePageAndSize(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw invalid();
        }
    }

    private static void validateDetectedRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid();
        }
    }

    private static void validateSort(String sort) {
        String field = sortField(sort);
        sortDirection(sort);
        if (FORBIDDEN_SORT_FIELDS.contains(field) || !ALLOWED_SORT_FIELDS.contains(field)) {
            throw invalid();
        }
    }

    private static String sortField(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !StringUtils.hasText(parts[0])) {
            throw invalid();
        }
        return parts[0].trim();
    }

    private static Sort.Direction sortDirection(String sort) {
        String[] parts = sort.split(",", -1);
        String value = parts.length == 1 ? "desc" : parts[1].trim();
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalid();
        };
    }

    private static String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static SuspiciousTransactionReadValidationException invalid() {
        return new SuspiciousTransactionReadValidationException("INVALID_SUSPICIOUS_TRANSACTION_QUERY");
    }
}
