package com.frauddetection.alert.audit;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
class AuditEventQueryParser {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final Clock clock;

    AuditEventQueryParser() {
        this(Clock.systemUTC());
    }

    AuditEventQueryParser(Clock clock) {
        this.clock = clock;
    }

    AuditEventQuery parse(
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            String from,
            String to,
            Integer limit
    ) {
        List<String> errors = new ArrayList<>();
        int normalizedLimit = parseLimit(limit, errors);
        AuditAction parsedEventType = parseEnum(eventType, AuditAction.class, "event_type", errors);
        AuditResourceType parsedResourceType = parseEnum(resourceType, AuditResourceType.class, "resource_type", errors);
        Instant parsedFrom = parseInstant(from, "from", errors);
        Instant parsedTo = parseInstant(to, "to", errors);

        if (parsedFrom != null && parsedTo == null) {
            parsedTo = Instant.now(clock);
        }
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            errors.add("from: must be before or equal to to");
        }
        if (!errors.isEmpty()) {
            throw new InvalidAuditEventQueryException(errors);
        }
        return new AuditEventQuery(
                parsedEventType,
                normalizeExact(actorId),
                parsedResourceType,
                normalizeExact(resourceId),
                parsedFrom,
                parsedTo,
                normalizedLimit
        );
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
        String normalized = normalizeExact(value);
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

    private <T extends Enum<T>> T parseEnum(String value, Class<T> type, String field, List<String> errors) {
        String normalized = normalizeExact(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add(field + ": unsupported value");
            return null;
        }
    }

    private String normalizeExact(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
