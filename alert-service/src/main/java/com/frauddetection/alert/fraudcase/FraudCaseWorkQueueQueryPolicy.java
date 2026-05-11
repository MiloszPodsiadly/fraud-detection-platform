package com.frauddetection.alert.fraudcase;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FraudCaseWorkQueueQueryPolicy {

    public static final int MAX_PAGE_SIZE = 100;
    public static final String DEFAULT_SORT = "createdAt,desc";
    public static final String TIE_BREAKER_FIELD = "_id";
    public static final Set<String> ALLOWED_QUERY_PARAMS = Set.of(
            "page",
            "size",
            "sort",
            "status",
            "assignee",
            "assignedInvestigatorId",
            "priority",
            "riskLevel",
            "createdFrom",
            "createdTo",
            "updatedFrom",
            "updatedTo",
            "linkedAlertId"
    );
    public static final Set<String> SINGLE_VALUE_PARAMS = ALLOWED_QUERY_PARAMS;
    public static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "priority", "riskLevel", "caseNumber");

    private FraudCaseWorkQueueQueryPolicy() {
    }

    public static void validateAllowedParameters(MultiValueMap<String, String> requestParams) {
        List<String> unsupported = requestParams.keySet().stream()
                .filter(param -> !ALLOWED_QUERY_PARAMS.contains(param))
                .toList();
        if (!unsupported.isEmpty()) {
            throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_FILTER", "Unsupported fraud case work queue filter.");
        }
    }

    public static void validateSingleValueParameters(MultiValueMap<String, String> requestParams) {
        List<String> duplicated = requestParams.entrySet().stream()
                .filter(entry -> SINGLE_VALUE_PARAMS.contains(entry.getKey()))
                .filter(entry -> entry.getValue() != null && entry.getValue().size() > 1)
                .map(java.util.Map.Entry::getKey)
                .toList();
        if (!duplicated.isEmpty()) {
            throw new FraudCaseWorkQueueQueryException("DUPLICATE_QUERY_PARAM", "Duplicate fraud case work queue query parameter.");
        }
    }

    public static void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new FraudCaseWorkQueueQueryException("INVALID_PAGE_REQUEST", "Invalid fraud case work queue page request.");
        }
    }

    public static void validateRange(String field, Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new FraudCaseWorkQueueQueryException("INVALID_FILTER_RANGE", "Invalid " + field + " filter range.");
        }
    }

    public static Sort.Order sortOrder(String sort) {
        String value = StringUtils.hasText(sort) ? sort.trim() : DEFAULT_SORT;
        String[] parts = value.split(",");
        if (parts.length > 2 || !SORT_FIELDS.contains(parts[0])) {
            throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_SORT_FIELD", "Unsupported fraud case work queue sort field.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            try {
                direction = Sort.Direction.fromString(parts[1].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_SORT_DIRECTION", "Unsupported fraud case work queue sort direction.");
            }
        }
        return new Sort.Order(direction, parts[0]);
    }

    public static Pageable boundedPageable(int page, int size, Sort.Order sortOrder) {
        validatePagination(page, size);
        return PageRequest.of(page, size, Sort.by(sortOrder));
    }

    public static Sort stableSort(Sort requestedSort) {
        Sort.Order primary = requestedSort.stream()
                .filter(order -> SORT_FIELDS.contains(order.getProperty()))
                .findFirst()
                .orElseThrow(() -> new FraudCaseWorkQueueQueryException(
                        "UNSUPPORTED_SORT_FIELD",
                        "Unsupported fraud case work queue sort field."
                ));
        return Sort.by(primary, Sort.Order.asc(TIE_BREAKER_FIELD));
    }
}
