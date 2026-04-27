package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.InvalidAuditEventQueryException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
class AuditEvidenceExportQueryParser {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ALLOWED_SOURCE_SERVICES = Set.of("alert-service");

    AuditEvidenceExportQuery parse(String from, String to, String sourceService, Integer limit) {
        List<String> errors = new ArrayList<>();
        Instant parsedFrom = parseRequiredInstant(from, "from", errors);
        Instant parsedTo = parseRequiredInstant(to, "to", errors);
        String parsedSourceService = parseRequiredSourceService(sourceService, errors);
        int parsedLimit = parseLimit(limit, errors);
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            errors.add("from: must be before or equal to to");
        }
        if (!errors.isEmpty()) {
            throw new InvalidAuditEventQueryException(errors);
        }
        return new AuditEvidenceExportQuery(
                parsedFrom,
                parsedTo,
                parsedSourceService,
                "source_service:" + parsedSourceService,
                parsedLimit
        );
    }

    private Instant parseRequiredInstant(String value, String field, List<String> errors) {
        if (!StringUtils.hasText(value)) {
            errors.add(field + ": is required");
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            errors.add(field + ": must be an ISO-8601 timestamp");
            return null;
        }
    }

    private String parseRequiredSourceService(String value, List<String> errors) {
        if (!StringUtils.hasText(value)) {
            errors.add("source_service: is required");
            return "alert-service";
        }
        String normalized = value.trim();
        if (!ALLOWED_SOURCE_SERVICES.contains(normalized)) {
            errors.add("source_service: unsupported value");
            return "alert-service";
        }
        return normalized;
    }

    private int parseLimit(Integer value, List<String> errors) {
        int parsed = value == null ? DEFAULT_LIMIT : value;
        if (parsed <= 0) {
            errors.add("limit: must be greater than 0");
        }
        if (parsed > MAX_LIMIT) {
            errors.add("limit: must be less than or equal to " + MAX_LIMIT);
        }
        return parsed;
    }
}
