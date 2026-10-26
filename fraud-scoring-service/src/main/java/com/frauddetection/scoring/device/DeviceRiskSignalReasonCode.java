package com.frauddetection.scoring.device;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Fixed, non-sensitive reason codes for the bounded Device Risk signal diagnostics. */
public enum DeviceRiskSignalReasonCode {
    DEVICE_EVIDENCE_NOT_ADMITTED,
    DEVICE_EVIDENCE_UNAVAILABLE,
    DEVICE_EVIDENCE_UNTRUSTED,
    DEVICE_SCORE_NOT_CALIBRATED,
    DEVICE_SIGNALS_UNSUPPORTED,
    DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW,
    DEVICE_NOVELTY_SEEN_IN_WINDOW,
    DEVICE_NOVELTY_HISTORY_INCOMPLETE,
    DEVICE_NOVELTY_HISTORY_UNAVAILABLE,
    DEVICE_NOVELTY_HISTORY_INVALID,
    DEVICE_NOVELTY_UNTRUSTED_SOURCE,
    DEVICE_SWITCH_OBSERVED,
    DEVICE_SWITCH_NOT_OBSERVED,
    DEVICE_SWITCH_HISTORY_INCOMPLETE,
    DEVICE_SWITCH_HISTORY_UNAVAILABLE,
    DEVICE_SWITCH_HISTORY_INVALID,
    DEVICE_SWITCH_UNTRUSTED_SOURCE,
    DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE,
    DEVICE_SHARED_DEVICE_UNTRUSTED_SOURCE,
    DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE,
    DEVICE_COUNTRY_MISMATCH_UNTRUSTED_SOURCE,
    DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE,
    DEVICE_PROXY_OR_VPN_UNTRUSTED_SOURCE;

    public String wireValue() {
        return name();
    }

    public static boolean owns(String wireValue) {
        return wireValue != null && DEVICE_CODES.contains(wireValue);
    }

    private static final Set<String> DEVICE_CODES = Arrays.stream(values())
            .map(DeviceRiskSignalReasonCode::wireValue)
            .collect(Collectors.toUnmodifiableSet());

    static String forOutcome(DeviceRiskSignalPolicy.Signal signal, DeviceRiskSignalPolicy.Reason reason) {
        return switch (signal) {
            case DEVICE_NOT_SEEN_RECENTLY -> switch (reason) {
                case NOT_SEEN_IN_WINDOW -> "DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW";
                case SEEN_IN_WINDOW -> "DEVICE_NOVELTY_SEEN_IN_WINDOW";
                case HISTORY_INCOMPLETE -> "DEVICE_NOVELTY_HISTORY_INCOMPLETE";
                case HISTORY_UNAVAILABLE -> "DEVICE_NOVELTY_HISTORY_UNAVAILABLE";
                case HISTORY_INVALID -> "DEVICE_NOVELTY_HISTORY_INVALID";
                case UNTRUSTED_SOURCE -> "DEVICE_NOVELTY_UNTRUSTED_SOURCE";
                case SWITCH_OBSERVED, NO_SWITCH_OBSERVED, UNSUPPORTED_SOURCE -> throw invalidCombination();
            };
            case DEVICE_SWITCH -> switch (reason) {
                case SWITCH_OBSERVED -> "DEVICE_SWITCH_OBSERVED";
                case NO_SWITCH_OBSERVED -> "DEVICE_SWITCH_NOT_OBSERVED";
                case HISTORY_INCOMPLETE -> "DEVICE_SWITCH_HISTORY_INCOMPLETE";
                case HISTORY_UNAVAILABLE -> "DEVICE_SWITCH_HISTORY_UNAVAILABLE";
                case HISTORY_INVALID -> "DEVICE_SWITCH_HISTORY_INVALID";
                case UNTRUSTED_SOURCE -> "DEVICE_SWITCH_UNTRUSTED_SOURCE";
                case NOT_SEEN_IN_WINDOW, SEEN_IN_WINDOW, UNSUPPORTED_SOURCE -> throw invalidCombination();
            };
            case OTHER_CUSTOMERS -> unsupportedReason("DEVICE_SHARED_DEVICE", reason);
            case COUNTRY_MISMATCH -> unsupportedReason("DEVICE_COUNTRY_MISMATCH", reason);
            case PROXY_OR_VPN -> unsupportedReason("DEVICE_PROXY_OR_VPN", reason);
        };
    }

    private static String unsupportedReason(String signal, DeviceRiskSignalPolicy.Reason reason) {
        return switch (reason) {
            case UNSUPPORTED_SOURCE -> signal + "_UNSUPPORTED_SOURCE";
            case UNTRUSTED_SOURCE -> signal + "_UNTRUSTED_SOURCE";
            case NOT_SEEN_IN_WINDOW, SEEN_IN_WINDOW, SWITCH_OBSERVED, NO_SWITCH_OBSERVED,
                    HISTORY_INCOMPLETE, HISTORY_UNAVAILABLE, HISTORY_INVALID -> throw invalidCombination();
        };
    }

    private static IllegalArgumentException invalidCombination() {
        return new IllegalArgumentException("DEVICE_POLICY_REASON_CODE_COMBINATION");
    }
}
