package com.frauddetection.scoring.engine;

import java.util.Set;
import java.util.regex.Pattern;

final class FraudEngineDescriptorValuePolicy {

    private static final Pattern MACHINE_READABLE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Set<String> CANONICAL_ENGINE_LANGUAGES = Set.of(
            "java",
            "python",
            "go",
            "kotlin",
            "scala",
            "javascript",
            "other"
    );

    private FraudEngineDescriptorValuePolicy() {
    }

    static String requireMachineReadableId(String value, String fieldName) {
        if (value == null) {
            throw new NullPointerException(fieldName + " is required");
        }
        if (!MACHINE_READABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a bounded machine-readable identifier using [A-Za-z0-9_.:-]{1,128}"
            );
        }
        return value;
    }

    static String requireEngineLanguage(String value) {
        if (value == null || !CANONICAL_ENGINE_LANGUAGES.contains(value)) {
            throw new IllegalArgumentException(
                    "engineLanguage must be a canonical lowercase allowlisted language"
            );
        }
        return value;
    }
}
