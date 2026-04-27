package com.frauddetection.alert.audit;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
class AuditIntegrityQueryParser {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    private static final Set<String> ALLOWED_SOURCE_SERVICES = Set.of("alert-service");

    static String partitionKey(String sourceService) {
        return "source_service:" + sourceService;
    }

    AuditIntegrityQuery parse(String from, String to, String sourceService, String mode, Integer limit) {
        List<String> errors = new ArrayList<>();
        int normalizedLimit = parseLimit(limit, errors);
        Instant parsedFrom = parseInstant(from, "from", errors);
        Instant parsedTo = parseInstant(to, "to", errors);
        String parsedSourceService = parseSourceService(sourceService, errors);
        AuditIntegrityVerificationMode parsedMode = parseMode(mode, parsedFrom, parsedTo, errors);

        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            errors.add("from: must be before or equal to to");
        }
        if (!errors.isEmpty()) {
            throw new InvalidAuditEventQueryException(errors);
        }
        return new AuditIntegrityQuery(parsedFrom, parsedTo, parsedSourceService, parsedMode, normalizedLimit);
    }

    AuditIntegrityQuery parse(String from, String to, String sourceService, Integer limit) {
        return parse(from, to, sourceService, null, limit);
    }

    private int parseLimit(Integer limit, List<String> errors) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value <= 0) {
            errors.add("limit: must be greater than 0");
        }
        if (value > MAX_LIMIT) {
            errors.add("limit: must be less than or equal to " + MAX_LIMIT);
        }
        return value;
    }

    private Instant parseInstant(String value, String field, List<String> errors) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException exception) {
            errors.add(field + ": must be an ISO-8601 timestamp");
            return null;
        }
    }

    private String parseSourceService(String value, List<String> errors) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        if (!ALLOWED_SOURCE_SERVICES.contains(normalized)) {
            errors.add("source_service: unsupported value");
            return null;
        }
        return normalized;
    }

    private AuditIntegrityVerificationMode parseMode(
            String value,
            Instant from,
            Instant to,
            List<String> errors
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            return from != null || to != null
                    ? AuditIntegrityVerificationMode.WINDOW
                    : AuditIntegrityVerificationMode.HEAD;
        }
        try {
            return AuditIntegrityVerificationMode.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException exception) {
            errors.add("mode: unsupported value");
            return AuditIntegrityVerificationMode.HEAD;
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
