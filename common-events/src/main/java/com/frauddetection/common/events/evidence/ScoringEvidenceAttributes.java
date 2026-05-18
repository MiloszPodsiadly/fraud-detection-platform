package com.frauddetection.common.events.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScoringEvidenceAttributes {

    private static final int MAX_STRING_LENGTH = 256;
    private static final List<String> SENSITIVE_EXACT_KEYS = List.of(
            "customerid",
            "accountid",
            "cardnumber",
            "iban",
            "pesel",
            "ssn",
            "email",
            "phone",
            "address",
            "fullname",
            "featuresnapshot",
            "modelpayload",
            "rawmodelpayload",
            "rawunsupportedreasoncode",
            "fallbackreason",
            "rawfallbackreason"
    );
    private static final List<String> SENSITIVE_KEY_MARKERS = List.of("raw", "payload");

    private ScoringEvidenceAttributes() {
    }

    public static Map<String, Object> safeCopy(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safeAttributes = new LinkedHashMap<>();
        attributes.forEach((key, value) -> safeAttributes.put(validateKey(key), safeValue(key, value)));
        return Map.copyOf(safeAttributes);
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Scoring evidence attribute key is required.");
        }
        String normalized = key.replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
        for (String exactKey : SENSITIVE_EXACT_KEYS) {
            if (normalized.equals(exactKey)) {
                throw new IllegalArgumentException("Unsafe scoring evidence attribute key: " + key);
            }
        }
        for (String marker : SENSITIVE_KEY_MARKERS) {
            if (normalized.contains(marker)) {
                throw new IllegalArgumentException("Unsafe scoring evidence attribute key: " + key);
            }
        }
        return key;
    }

    private static Object safeValue(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Scoring evidence attribute value is required for key: " + key);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String stringValue) {
            return safeString(key, stringValue);
        }
        if (value instanceof List<?> values) {
            List<Object> safeValues = new ArrayList<>();
            for (Object item : values) {
                if (item instanceof Boolean || item instanceof Number) {
                    safeValues.add(item);
                } else if (item instanceof String stringItem) {
                    safeValues.add(safeString(key, stringItem));
                } else {
                    throw new IllegalArgumentException("Unsafe scoring evidence attribute list value for key: " + key);
                }
            }
            return List.copyOf(safeValues);
        }
        throw new IllegalArgumentException("Unsafe scoring evidence attribute value for key: " + key);
    }

    private static String safeString(String key, String value) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Scoring evidence attribute value is too long for key: " + key);
        }
        return value;
    }
}
