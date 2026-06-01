package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.reason.ReasonCode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class EngineIntelligenceValuePolicy {
    static final int MAX_ENGINES = 2;
    static final int MAX_DIAGNOSTIC_SIGNALS = 5;
    static final int MAX_WARNINGS = 10;
    static final int MAX_REASON_CODES_PER_ENGINE = 5;
    static final int MAX_STRING_LENGTH = 128;

    private static final Map<String, FraudEngineType> ENGINE_TYPES = Map.of(
            "rules.primary", FraudEngineType.RULES,
            "ml.python.primary", FraudEngineType.ML_MODEL
    );
    private static final Set<String> ALLOWED_REASON_CODES = allowedReasonCodes();
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
        FraudEngineType expected = ENGINE_TYPES.get(engineId);
        if (expected == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
        }
        if (engineType != expected) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_ENGINE_TYPE_MISMATCH");
        }
    }

    static String requireReasonCode(String reasonCode) {
        requireBoundedSafeText(reasonCode, "ENGINE_INTELLIGENCE_REASON_CODE_INVALID");
        if (!ALLOWED_REASON_CODES.contains(reasonCode)) {
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

    private static void requireBoundedSafeText(String value, String errorCode) {
        if (value == null || value.isBlank() || value.length() > MAX_STRING_LENGTH || containsForbiddenText(value)) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    private static boolean containsForbiddenText(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return FORBIDDEN_COMPACT_TEXT.stream().anyMatch(normalized::contains)
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static Set<String> allowedReasonCodes() {
        Stream<String> sharedReasonCodes = Stream.of(ReasonCode.values())
                .filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN)
                .map(ReasonCode::wireValue);
        Stream<String> engineReasonCodes = Stream.of(
                "ML_MODEL_SIGNAL",
                "ML_MODEL_UNAVAILABLE",
                "ML_MODEL_TIMEOUT",
                "ML_MODEL_INVALID_RESPONSE",
                "ML_SCORE_MISSING",
                "ML_SCORE_OUT_OF_RANGE",
                "ML_MODEL_METADATA_MISSING",
                "ML_AVAILABILITY_METADATA_MISSING",
                "ML_AVAILABILITY_METADATA_INVALID",
                "ML_CLIENT_ERROR",
                "DEVICE_NOVELTY_SIGNAL",
                "COUNTRY_MISMATCH_SIGNAL",
                "PROXY_OR_VPN_SIGNAL",
                "RAPID_TRANSFER_BURST_SIGNAL",
                "HIGH_RISK_FLAGS_PRESENT",
                "VELOCITY_THRESHOLD_EXCEEDED",
                "AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED",
                "RAPID_TRANSFER_PATTERN_MATCHED",
                "FEATURE_STATUS_INVALID",
                "FEATURE_STATUS_WRONG_ACCESSOR",
                "FEATURE_STATUS_NOT_ALLOWED",
                "ORCHESTRATOR_ENGINE_EXCEPTION",
                "ORCHESTRATOR_ENGINE_NULL_RESULT",
                "ORCHESTRATOR_ENGINE_REJECTED",
                "ORCHESTRATOR_ENGINE_TIMEOUT"
        );
        return Stream.concat(sharedReasonCodes, engineReasonCodes).collect(Collectors.toUnmodifiableSet());
    }
}
