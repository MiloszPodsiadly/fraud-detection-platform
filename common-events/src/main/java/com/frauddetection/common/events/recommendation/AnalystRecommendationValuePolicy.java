package com.frauddetection.common.events.recommendation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class AnalystRecommendationValuePolicy {

    private static final int MAX_CODE_LENGTH = 128;
    private static final Set<String> FORBIDDEN_COMPACT_TEXT = Set.of(
            "rawpayload",
            "rawrequest",
            "rawresponse",
            "rawevidence",
            "featurevector",
            "groundtruth",
            "traininglabel",
            "finaldecision",
            "paymentdecision",
            "paymentauthorization",
            "approve",
            "decline",
            "block",
            "token",
            "secret",
            "apikey",
            "password",
            "stacktrace",
            "exception"
    );

    private AnalystRecommendationValuePolicy() {
    }

    static void requireBoundedCode(String value, String errorCode) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_CODE_LENGTH
                || !value.matches("[A-Z0-9_]+")
                || containsForbiddenText(value)) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    static <T> List<T> copyBounded(List<T> source, int maximum, String fieldName) {
        Objects.requireNonNull(source, fieldName + " is required");
        if (source.size() > maximum) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_" + fieldName.toUpperCase(Locale.ROOT) + "_LIMIT_EXCEEDED");
        }
        for (T item : source) {
            Objects.requireNonNull(item, fieldName + " must not contain null entries");
        }
        return List.copyOf(source);
    }

    private static boolean containsForbiddenText(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return FORBIDDEN_COMPACT_TEXT.stream().anyMatch(normalized::contains)
                || value.chars().anyMatch(Character::isISOControl);
    }
}
