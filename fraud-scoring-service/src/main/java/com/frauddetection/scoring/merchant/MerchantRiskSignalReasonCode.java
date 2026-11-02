package com.frauddetection.scoring.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Signal;
import com.frauddetection.scoring.merchant.MerchantRiskSignalPolicy.State;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Sole owner of internal merchant diagnostic codes. None is a public EI reason or a fraud verdict. */
enum MerchantRiskSignalReasonCode {
    MERCHANT_NOT_ADMITTED(State.UNAVAILABLE, null),
    MERCHANT_NO_PROFILE(State.UNAVAILABLE, null),
    MERCHANT_CLIENT_ASSERTION(State.UNTRUSTED, null),
    MERCHANT_SYNTHETIC_INPUT(State.UNTRUSTED, null),
    MERCHANT_PROFILE_ACCESS_TIMEOUT(State.UNAVAILABLE, null),
    MERCHANT_PROFILE_ACCESS_REJECTED(State.UNAVAILABLE, null),
    MERCHANT_PROFILE_ACCESS_UNAVAILABLE(State.UNAVAILABLE, null),
    MERCHANT_SCORE_NOT_CALIBRATED(State.UNAVAILABLE, null),
    MERCHANT_SIGNALS_UNAVAILABLE(State.UNAVAILABLE, null),
    MERCHANT_SCOPE_MISMATCH(State.UNAVAILABLE, null),
    MERCHANT_OCCURRENCE_MISMATCH(State.UNAVAILABLE, null),
    MERCHANT_WINDOW_MISMATCH(State.UNAVAILABLE, null),
    MERCHANT_MISSING_COVERAGE(State.UNAVAILABLE, null),
    MERCHANT_SOURCE_GAP(State.UNAVAILABLE, null),
    MERCHANT_RETENTION_EXPIRED(State.UNAVAILABLE, null),
    MERCHANT_HISTORY_MISSING(State.UNAVAILABLE, null),
    MERCHANT_CAPACITY_EXCEEDED(State.UNAVAILABLE, null),
    MERCHANT_DATA_CONFLICT(State.UNAVAILABLE, null),
    MERCHANT_INVALID_TIME(State.UNAVAILABLE, null),
    MERCHANT_FRAUD_RATE_LABELS_UNSUPPORTED(State.UNSUPPORTED, Signal.MERCHANT_FRAUD_RATE),
    MERCHANT_SPIKE_METHOD_NOT_VALIDATED(State.UNSUPPORTED, Signal.MERCHANT_ACTIVITY_SPIKE),
    MERCHANT_CATEGORY_METHOD_NOT_VALIDATED(State.UNSUPPORTED, Signal.MERCHANT_CATEGORY_CONTEXT),
    MERCHANT_CONCENTRATION_POPULATION_UNAVAILABLE(State.UNAVAILABLE, Signal.MERCHANT_POPULATION_CONCENTRATION),
    MERCHANT_FIRST_OBSERVED_IN_WINDOW(State.AVAILABLE, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW),
    MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW(State.AVAILABLE, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW);

    private final State state;
    private final Signal owner;

    MerchantRiskSignalReasonCode(State state, Signal owner) {
        this.state = state;
        this.owner = owner;
    }

    State state() { return state; }
    boolean supports(Signal signal) { return signal != null && (owner == null || owner == signal); }
    String wireValue() { return name(); }

    private static final Set<String> CODES = Arrays.stream(values())
            .map(MerchantRiskSignalReasonCode::wireValue).collect(Collectors.toUnmodifiableSet());

    static boolean owns(String code) { return code != null && CODES.contains(code); }

    static MerchantRiskSignalReasonCode fromProfile(MerchantProfile.Reason reason) {
        return switch (reason) {
            case NOT_ADMITTED -> MERCHANT_NOT_ADMITTED;
            case MISSING_COVERAGE -> MERCHANT_MISSING_COVERAGE;
            case SOURCE_GAP -> MERCHANT_SOURCE_GAP;
            case RETENTION_EXPIRED -> MERCHANT_RETENTION_EXPIRED;
            case HISTORY_MISSING -> MERCHANT_HISTORY_MISSING;
            case CAPACITY_EXCEEDED -> MERCHANT_CAPACITY_EXCEEDED;
            case SCOPE_MISMATCH -> MERCHANT_SCOPE_MISMATCH;
            case OCCURRENCE_MISMATCH -> MERCHANT_OCCURRENCE_MISMATCH;
            case WINDOW_MISMATCH -> MERCHANT_WINDOW_MISMATCH;
            case DATA_CONFLICT -> MERCHANT_DATA_CONFLICT;
            case INVALID_TIME -> MERCHANT_INVALID_TIME;
            case NONE -> throw new IllegalArgumentException("MERCHANT_POLICY_REASON_STATE");
        };
    }
}
