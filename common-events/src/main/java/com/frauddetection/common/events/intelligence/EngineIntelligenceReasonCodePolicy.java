package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.reason.ReasonCode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Authoritative ownership policy for bounded public Engine Intelligence reason codes. */
public final class EngineIntelligenceReasonCodePolicy {
    private static final Set<String> NON_DEVICE_SHARED_REASON_CODES = Stream.of(ReasonCode.values())
            .filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN)
            .map(ReasonCode::wireValue)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> RULES_REASON_CODES = Set.of(
            "DEVICE_NOVELTY_SIGNAL", "COUNTRY_MISMATCH_SIGNAL", "PROXY_OR_VPN_SIGNAL",
            "RAPID_TRANSFER_BURST_SIGNAL", "HIGH_RISK_FLAGS_PRESENT", "VELOCITY_THRESHOLD_EXCEEDED",
            "AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED", "RAPID_TRANSFER_PATTERN_MATCHED", "FEATURE_STATUS_INVALID",
            "FEATURE_STATUS_WRONG_ACCESSOR", "FEATURE_STATUS_NOT_ALLOWED");
    private static final Set<String> ML_REASON_CODES = Set.of(
            "ML_MODEL_SIGNAL", "ML_MODEL_TIMEOUT", "ML_MODEL_INVALID_RESPONSE",
            "ML_SCORE_MISSING", "ML_SCORE_OUT_OF_RANGE", "ML_INFERENCE_TIMESTAMP_MISSING",
            "ML_MODEL_METADATA_MISSING", "ML_AVAILABILITY_METADATA_MISSING",
            "ML_AVAILABILITY_METADATA_INVALID", "ML_CLIENT_ERROR");
    private static final Set<String> VELOCITY_REASON_CODES = Set.of(
            "VELOCITY_FEATURES_UNAVAILABLE", "VELOCITY_FEATURE_TYPE_INVALID",
            "VELOCITY_FEATURE_VALUE_INVALID", "VELOCITY_FEATURES_INCONSISTENT");
    private static final Set<String> DEVICE_REASON_CODES = Set.of(
            "DEVICE_EVIDENCE_NOT_ADMITTED", "DEVICE_EVIDENCE_UNAVAILABLE", "DEVICE_EVIDENCE_PARTIAL",
            "DEVICE_EVIDENCE_UNTRUSTED", "DEVICE_SCORE_NOT_CALIBRATED", "DEVICE_SIGNALS_UNSUPPORTED",
            "DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW", "DEVICE_NOVELTY_SEEN_IN_WINDOW",
            "DEVICE_NOVELTY_HISTORY_INCOMPLETE", "DEVICE_NOVELTY_HISTORY_UNAVAILABLE",
            "DEVICE_NOVELTY_HISTORY_INVALID", "DEVICE_NOVELTY_UNTRUSTED_SOURCE", "DEVICE_SWITCH_OBSERVED",
            "DEVICE_SWITCH_NOT_OBSERVED", "DEVICE_SWITCH_HISTORY_INCOMPLETE",
            "DEVICE_SWITCH_HISTORY_UNAVAILABLE", "DEVICE_SWITCH_HISTORY_INVALID",
            "DEVICE_SWITCH_UNTRUSTED_SOURCE", "DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE",
            "DEVICE_SHARED_DEVICE_UNTRUSTED_SOURCE", "DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE",
            "DEVICE_COUNTRY_MISMATCH_UNTRUSTED_SOURCE", "DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE",
            "DEVICE_PROXY_OR_VPN_UNTRUSTED_SOURCE");
    private static final Set<String> SHARED_ORCHESTRATOR_REASON_CODES = Set.of(
            "ORCHESTRATOR_ENGINE_EXCEPTION", "ORCHESTRATOR_ENGINE_NULL_RESULT",
            "ORCHESTRATOR_ENGINE_REJECTED", "ORCHESTRATOR_ENGINE_TIMEOUT");
    private static final Map<String, Set<String>> REASON_CODES_BY_ENGINE = Map.of(
            FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
            merge(NON_DEVICE_SHARED_REASON_CODES, RULES_REASON_CODES, SHARED_ORCHESTRATOR_REASON_CODES),
            FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
            merge(NON_DEVICE_SHARED_REASON_CODES, ML_REASON_CODES, SHARED_ORCHESTRATOR_REASON_CODES),
            FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
            merge(NON_DEVICE_SHARED_REASON_CODES, VELOCITY_REASON_CODES, SHARED_ORCHESTRATOR_REASON_CODES),
            FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID,
            merge(DEVICE_REASON_CODES, SHARED_ORCHESTRATOR_REASON_CODES)
    );
    private static final Set<String> KNOWN_REASON_CODES = REASON_CODES_BY_ENGINE.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    private EngineIntelligenceReasonCodePolicy() {
    }

    public static boolean isKnownReasonCode(String reasonCode) {
        return KNOWN_REASON_CODES.contains(reasonCode);
    }

    public static boolean isAllowedForEngine(String engineId, String reasonCode) {
        return REASON_CODES_BY_ENGINE.getOrDefault(engineId, Set.of()).contains(reasonCode);
    }

    public static void requireOwnedByEngine(String engineId, String reasonCode) {
        if (!isAllowedForEngine(engineId, reasonCode)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
        }
    }

    public static Set<String> deviceReasonCodes() {
        return DEVICE_REASON_CODES;
    }

    public static Set<String> sharedOrchestratorReasonCodes() {
        return SHARED_ORCHESTRATOR_REASON_CODES;
    }

    static Set<String> reasonCodesForEngine(String engineId) {
        return REASON_CODES_BY_ENGINE.getOrDefault(engineId, Set.of());
    }

    @SafeVarargs
    private static Set<String> merge(Set<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (Set<String> group : groups) {
            merged.addAll(group);
        }
        return Set.copyOf(merged);
    }
}
