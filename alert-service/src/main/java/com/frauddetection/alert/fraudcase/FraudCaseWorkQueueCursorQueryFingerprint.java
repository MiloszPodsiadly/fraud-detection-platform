package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.idempotency.IdempotencyCanonicalHasher;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FraudCaseWorkQueueCursorQueryFingerprint {

    private FraudCaseWorkQueueCursorQueryFingerprint() {
    }

    public static String hash(
            FraudCaseStatus status,
            String normalizedAssignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            String linkedAlertId,
            Sort.Order sortOrder,
            int cursorVersion
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("version", cursorVersion);
        canonical.put("status", enumName(status));
        canonical.put("assignee", normalize(normalizedAssignee));
        canonical.put("priority", enumName(priority));
        canonical.put("riskLevel", enumName(riskLevel));
        canonical.put("createdFrom", instant(createdFrom));
        canonical.put("createdTo", instant(createdTo));
        canonical.put("updatedFrom", instant(updatedFrom));
        canonical.put("updatedTo", instant(updatedTo));
        canonical.put("linkedAlertId", normalize(linkedAlertId));
        canonical.put("sortField", sortOrder == null ? null : sortOrder.getProperty());
        canonical.put("sortDirection", sortOrder == null ? null : sortOrder.getDirection().name().toUpperCase(Locale.ROOT));
        return IdempotencyCanonicalHasher.hash(canonical);
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
