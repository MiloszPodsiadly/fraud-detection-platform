package com.frauddetection.scoring.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Signal;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.merchant.MerchantRiskSignalReasonCode.*;

/**
 * Disconnected pure diagnostics. Package access and a default-denied admission seam keep claimed
 * profiles from granting themselves authority. There is no production admission implementation.
 * The only supported predicate is institution/merchant/source-scoped absence in the explicit recent
 * pre-event window. It says nothing about first-ever activity, customer relationships or fraud risk.
 */
final class MerchantRiskSignalPolicy {
    static final int VERSION = 1;

    enum State { AVAILABLE, UNAVAILABLE, UNTRUSTED, UNSUPPORTED }
    enum Coverage { NO_USABLE_HISTORY, NO_SUPPORTED_SOURCE, INCOMPLETE_WINDOW, COMPLETE_RECENT_WINDOW, COMPLETE_COMPARISON_WINDOWS }
    enum ObservationScope { INSTITUTION_MERCHANT_SOURCE_IDENTITY_VERSION }
    enum RiskInference { NOT_CALIBRATED }

    /** Must independently authorize this exact profile, scope and occurrence; matching tokens are insufficient. */
    @FunctionalInterface
    interface ProfileAdmission {
        void requireProfile(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile);
    }

    record SignalOutcome(Signal signal, State state, Boolean observed, MerchantRiskSignalReasonCode reason, Coverage coverage) {
        SignalOutcome {
            require(signal != null && state != null && reason != null && coverage != null, "OUTCOME");
            require(reason.state() == state && reason.supports(signal), "OUTCOME_REASON");
            require((state == State.AVAILABLE) == (observed != null), "OUTCOME_VALUE");
            boolean coherentCoverage = switch (coverage) {
                case NO_USABLE_HISTORY -> state == State.UNAVAILABLE || state == State.UNTRUSTED;
                case NO_SUPPORTED_SOURCE -> signal == Signal.MERCHANT_FRAUD_RATE || signal == Signal.MERCHANT_CATEGORY_CONTEXT
                        || signal == Signal.MERCHANT_POPULATION_CONCENTRATION;
                case INCOMPLETE_WINDOW -> state == State.UNAVAILABLE
                        && (signal == Signal.MERCHANT_ACTIVITY_SPIKE || signal == Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW);
                case COMPLETE_RECENT_WINDOW -> state == State.AVAILABLE && signal == Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW;
                case COMPLETE_COMPARISON_WINDOWS -> reason == MERCHANT_SPIKE_METHOD_NOT_VALIDATED;
            };
            require(coherentCoverage, "OUTCOME_COVERAGE");
            if (state == State.AVAILABLE) {
                require(signal == Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW && coverage == Coverage.COMPLETE_RECENT_WINDOW
                        && reason == (observed ? MERCHANT_FIRST_OBSERVED_IN_WINDOW : MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW), "OUTCOME_VALUE");
            }
        }
        String reasonCode() { return reason.wireValue(); }
    }

    /** Safe evidence view: no institution/merchant/customer references, payload or history commitments. */
    record HistoryEvidence(int computationVersion, WindowCount recent, WindowCount baseline,
                           Instant coverageStart, Instant watermark, Instant retainedFrom, Instant evaluatedAt) { }

    record Evaluation(int policyVersion, HistoryEvidence evidence, List<SignalOutcome> signals) {
        Evaluation {
            require(policyVersion == VERSION && signals != null && signals.size() == Signal.values().length, "EVALUATION");
            signals = List.copyOf(signals);
            for (int index = 0; index < signals.size(); index++) {
                require(signals.get(index).signal() == Signal.values()[index], "SIGNAL_ORDER");
            }
            for (SignalOutcome signal : signals) {
                if (signal.state() == State.AVAILABLE) {
                    require(evidence != null && evidence.recent() != null
                            && evidence.recent().status() == MerchantProfile.Status.AVAILABLE
                            && signal.observed() == (evidence.recent().count() == 0), "EVIDENCE");
                }
                require(evidence != null || signal.coverage() == Coverage.NO_USABLE_HISTORY, "EVIDENCE");
            }
        }
        Integer score() { return null; }
        RiskInference riskInference() { return RiskInference.NOT_CALIBRATED; }
        ObservationScope observationScope() { return ObservationScope.INSTITUTION_MERCHANT_SOURCE_IDENTITY_VERSION; }
    }

    private final Duration recentWindow;
    private final Duration baselineWindow;
    private final ProfileAdmission admission;

    /** Explicit software windows, not production defaults or an approved risk methodology. Null admission denies every profile. */
    MerchantRiskSignalPolicy(Duration recentWindow, Duration baselineWindow, ProfileAdmission admission) {
        for (Duration window : new Duration[] {recentWindow, baselineWindow}) {
            require(window != null && !window.isNegative() && !window.isZero()
                    && window.getNano() % 1_000_000 == 0 && window.compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0, "WINDOWS");
        }
        require(recentWindow.plus(baselineWindow).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0, "WINDOWS");
        this.recentWindow = recentWindow;
        this.baselineWindow = baselineWindow;
        this.admission = admission;
    }

    Evaluation evaluate(ScopeClaim expectedScope, OccurrenceClaim expectedOccurrence, MerchantProfile profile) {
        if (admission == null || expectedScope == null || expectedOccurrence == null) { return blocked(MERCHANT_NOT_ADMITTED); }
        if (profile == null) { return blocked(MERCHANT_NO_PROFILE); }
        try {
            admission.requireProfile(expectedScope, expectedOccurrence, profile);
        } catch (RuntimeException exception) {
            // Do not propagate issuer messages, identifiers, payloads or causes.
            return blocked(MERCHANT_NOT_ADMITTED);
        }
        if (!expectedScope.equals(profile.scope())) { return blocked(MERCHANT_SCOPE_MISMATCH); }
        if (!expectedOccurrence.equals(profile.occurrence())) { return blocked(MERCHANT_OCCURRENCE_MISMATCH); }
        profile.canonicalBytes(); // The immutable input contract enforces its bounded shape; this grants no authority.
        Instant asOf = expectedOccurrence.eventTime();
        if (!profile.recent().start().equals(asOf.minus(recentWindow))
                || !profile.baseline().start().equals(asOf.minus(recentWindow).minus(baselineWindow))) {
            return blocked(MERCHANT_WINDOW_MISMATCH);
        }
        for (WindowCount window : new WindowCount[] {profile.recent(), profile.baseline()}) {
            if (window.status() == MerchantProfile.Status.UNAVAILABLE
                    && window.reason() != MerchantProfile.Reason.MISSING_COVERAGE
                    && window.reason() != MerchantProfile.Reason.RETENTION_EXPIRED) {
                return blocked(MerchantRiskSignalReasonCode.fromProfile(window.reason()));
            }
        }

        var provenance = profile.provenance();
        HistoryEvidence evidence = new HistoryEvidence(profile.computationVersion(), profile.recent(), profile.baseline(),
                provenance.coverageStart(), provenance.watermark(), provenance.retainedFrom(), provenance.evaluatedAt());
        return new Evaluation(VERSION, evidence, List.of(
                // No adjudicated mature labels, population coverage or selection/minimum-sample policy exists.
                outcome(Signal.MERCHANT_FRAUD_RATE, null, MERCHANT_FRAUD_RATE_LABELS_UNSUPPORTED, Coverage.NO_SUPPORTED_SOURCE),
                spike(profile),
                // MCC syntax alone cannot supply a versioned, effective-at taxonomy and approved risk methodology.
                outcome(Signal.MERCHANT_CATEGORY_CONTEXT, null, MERCHANT_CATEGORY_METHOD_NOT_VALIDATED, Coverage.NO_SUPPORTED_SOURCE),
                // No verified merchant-wide population denominator; customer-merchant Rules frequency is a different fact.
                outcome(Signal.MERCHANT_POPULATION_CONCENTRATION, null, MERCHANT_CONCENTRATION_POPULATION_UNAVAILABLE, Coverage.NO_SUPPORTED_SOURCE),
                firstObserved(profile.recent())));
    }

    /** Client/synthetic claims never enter the admitted-profile path, irrespective of their claimed facts. */
    Evaluation evaluateClaim(MerchantRiskEvidenceClaim claim) {
        if (claim == null) { return blocked(MERCHANT_NO_PROFILE); }
        return blocked(switch (claim.source().assertion()) {
            case CLIENT_ASSERTED -> MERCHANT_CLIENT_ASSERTION;
            case SYNTHETIC -> MERCHANT_SYNTHETIC_INPUT;
            case UNSPECIFIED -> MERCHANT_NOT_ADMITTED;
        });
    }

    private static SignalOutcome spike(MerchantProfile profile) {
        for (WindowCount window : new WindowCount[] {profile.recent(), profile.baseline()}) {
            if (window.status() != MerchantProfile.Status.AVAILABLE) {
                return outcome(Signal.MERCHANT_ACTIVITY_SPIKE, null,
                        MerchantRiskSignalReasonCode.fromProfile(window.reason()), Coverage.INCOMPLETE_WINDOW);
            }
        }
        // Counts alone do not validate count/volume choice, currency normalization, seasonality,
        // minimum samples or thresholds. In particular, zero baseline never becomes an infinite ratio.
        return outcome(Signal.MERCHANT_ACTIVITY_SPIKE, null, MERCHANT_SPIKE_METHOD_NOT_VALIDATED, Coverage.COMPLETE_COMPARISON_WINDOWS);
    }

    private static SignalOutcome firstObserved(WindowCount recent) {
        if (recent.status() != MerchantProfile.Status.AVAILABLE) {
            return outcome(Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW, null,
                    MerchantRiskSignalReasonCode.fromProfile(recent.reason()), Coverage.INCOMPLETE_WINDOW);
        }
        boolean absent = recent.count() == 0;
        return outcome(Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW, absent,
                absent ? MERCHANT_FIRST_OBSERVED_IN_WINDOW : MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW, Coverage.COMPLETE_RECENT_WINDOW);
    }

    private static Evaluation blocked(MerchantRiskSignalReasonCode reason) {
        return new Evaluation(VERSION, null, Arrays.stream(Signal.values())
                .map(signal -> outcome(signal, null, reason, Coverage.NO_USABLE_HISTORY)).toList());
    }

    private static SignalOutcome outcome(Signal signal, Boolean observed, MerchantRiskSignalReasonCode reason, Coverage coverage) {
        return new SignalOutcome(signal, reason.state(), observed, reason, coverage);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException("MERCHANT_POLICY_" + reason); }
    }
}
