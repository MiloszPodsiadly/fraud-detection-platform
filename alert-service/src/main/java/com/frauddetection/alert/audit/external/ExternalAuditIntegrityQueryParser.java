package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.InvalidAuditEventQueryException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
class ExternalAuditIntegrityQueryParser {

    private static final String DEFAULT_SOURCE_SERVICE = "alert-service";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ALLOWED_SOURCE_SERVICES = Set.of(DEFAULT_SOURCE_SERVICE);

    ExternalAuditIntegrityQuery parse(String sourceService, Integer limit) {
        List<String> errors = new ArrayList<>();
        String parsedSourceService = parseSourceService(sourceService, errors);
        int parsedLimit = parseLimit(limit, errors);
        if (!errors.isEmpty()) {
            throw new InvalidAuditEventQueryException(errors);
        }
        return new ExternalAuditIntegrityQuery(
                parsedSourceService,
                "source_service:" + parsedSourceService,
                parsedLimit
        );
    }

    private String parseSourceService(String value, List<String> errors) {
        String normalized = StringUtils.hasText(value) ? value.trim() : DEFAULT_SOURCE_SERVICE;
        if (!ALLOWED_SOURCE_SERVICES.contains(normalized)) {
            errors.add("source_service: unsupported value");
            return DEFAULT_SOURCE_SERVICE;
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
