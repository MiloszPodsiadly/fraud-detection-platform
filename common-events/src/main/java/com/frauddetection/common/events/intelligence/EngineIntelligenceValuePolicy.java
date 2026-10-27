package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class EngineIntelligenceValuePolicy {
    static final int MAX_DIAGNOSTIC_SIGNALS = 5;
    static final int MAX_WARNINGS = 10;
    static final int MAX_REASON_CODES_PER_ENGINE = 5;
    static final int MAX_STRING_LENGTH = 128;

    private static final Set<String> FORBIDDEN_COMPACT_TEXT = Set.of(
            "transactionid",
            "txnid",
            "customerid",
            "custid",
            "accountid",
            "acctid",
            "cardid",
            "merchantid",
            "rawpayload",
            "payload",
            "featurevector",
            "endpoint",
            "http://",
            "https://",
            "token",
            "secret",
            "apikey",
            "password",
            "stacktrace",
            "exception",
            "debug"
    );

    private EngineIntelligenceValuePolicy() {
    }

    static void requireEngineIdentity(String engineId, FraudEngineType engineType) {
        requireBoundedSafeText(engineId, "ENGINE_INTELLIGENCE_ENGINE_ID_INVALID");
        if (!FraudEngineIdentityContract.isKnownEngineId(engineId)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
        }
        if (!FraudEngineIdentityContract.hasExpectedType(engineId, engineType)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_ENGINE_TYPE_MISMATCH");
        }
    }

    static String requireReasonCode(String reasonCode) {
        requireBoundedSafeText(reasonCode, "ENGINE_INTELLIGENCE_REASON_CODE_INVALID");
        if (!EngineIntelligenceReasonCodePolicy.isKnownReasonCode(reasonCode)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED");
        }
        return reasonCode;
    }

    static <T> List<T> copyBounded(List<T> source, int maximum, String fieldName) {
        Objects.requireNonNull(source, fieldName + " is required");
        if (source.size() > maximum) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_" + fieldName.toUpperCase() + "_LIMIT_EXCEEDED");
        }
        for (T item : source) {
            Objects.requireNonNull(item, fieldName + " must not contain null entries");
        }
        return List.copyOf(source);
    }

    static String requireBoundedSafeText(String value, String errorCode) {
        if (value == null || value.isBlank() || value.length() > MAX_STRING_LENGTH || containsForbiddenText(value)) {
            throw new IllegalArgumentException(errorCode);
        }
        return value;
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
