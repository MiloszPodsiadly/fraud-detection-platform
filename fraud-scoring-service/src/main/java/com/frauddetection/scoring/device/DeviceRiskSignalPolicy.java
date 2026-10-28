package com.frauddetection.scoring.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure, internal interpretation of admitted device facts. It does not produce a fraud score. */
public final class DeviceRiskSignalPolicy {
    public static final int VERSION = 1;
    public static final Duration HISTORY_WINDOW = Duration.ofDays(30);

    public enum Signal { DEVICE_NOT_SEEN_RECENTLY, DEVICE_SWITCH, OTHER_CUSTOMERS, COUNTRY_MISMATCH, PROXY_OR_VPN }

    public enum Support { SUPPORTED, UNSUPPORTED }

    public enum EvidenceState { AVAILABLE, UNAVAILABLE, UNTRUSTED, INVALID, UNSUPPORTED }

    /** Coverage of the two supported history predicates; this is not fraud-risk confidence. */
    public enum EvidenceCoverage {
        NO_SUPPORTED_HISTORY,
        PARTIAL_SUPPORTED_HISTORY,
        COMPLETE_SUPPORTED_HISTORY
    }

    public enum RiskInference { NOT_CALIBRATED }

    /** Fixed internal outcomes; they map to bounded public diagnostic codes, never a fraud verdict. */
    public enum Reason {
        NOT_SEEN_IN_WINDOW, SEEN_IN_WINDOW, SWITCH_OBSERVED, NO_SWITCH_OBSERVED,
        HISTORY_INCOMPLETE, HISTORY_UNAVAILABLE, HISTORY_INVALID,
        UNTRUSTED_SOURCE, UNSUPPORTED_SOURCE
    }

    public record SignalOutcome(Signal signal, FraudEngineStatus status, Boolean observed, Reason reason) {
        public SignalOutcome {
            Objects.requireNonNull(signal);
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            if ((status == FraudEngineStatus.AVAILABLE) != (observed != null)) {
                throw new IllegalArgumentException("DEVICE_POLICY_OUTCOME_STATE");
            }
            boolean historySignal = signal == Signal.DEVICE_NOT_SEEN_RECENTLY || signal == Signal.DEVICE_SWITCH;
            boolean validReason = switch (status) {
                case AVAILABLE -> historySignal && (signal == Signal.DEVICE_NOT_SEEN_RECENTLY
                        ? reason == (observed ? Reason.NOT_SEEN_IN_WINDOW : Reason.SEEN_IN_WINDOW)
                        : reason == (observed ? Reason.SWITCH_OBSERVED : Reason.NO_SWITCH_OBSERVED));
                case UNAVAILABLE -> historySignal
                        ? reason == Reason.HISTORY_INCOMPLETE || reason == Reason.HISTORY_UNAVAILABLE
                            || reason == Reason.HISTORY_INVALID || reason == Reason.UNTRUSTED_SOURCE
                        : reason == Reason.UNSUPPORTED_SOURCE;
                case SKIPPED -> reason == Reason.UNTRUSTED_SOURCE;
                default -> false;
            };
            if (!validReason) {
                throw new IllegalArgumentException("DEVICE_POLICY_OUTCOME_REASON");
            }
        }

        public Support support() {
            return signal == Signal.DEVICE_NOT_SEEN_RECENTLY || signal == Signal.DEVICE_SWITCH
                    ? Support.SUPPORTED : Support.UNSUPPORTED;
        }

        public EvidenceState evidenceState() {
            if (status == FraudEngineStatus.AVAILABLE) {
                return EvidenceState.AVAILABLE;
            }
            if (reason == Reason.UNTRUSTED_SOURCE) {
                return EvidenceState.UNTRUSTED;
            }
            if (reason == Reason.HISTORY_INVALID) {
                return EvidenceState.INVALID;
            }
            if (reason == Reason.UNSUPPORTED_SOURCE) {
                return EvidenceState.UNSUPPORTED;
            }
            return EvidenceState.UNAVAILABLE;
        }

        public String reasonCode() {
            return DeviceRiskSignalReasonCode.forOutcome(signal, reason);
        }
    }

    /** score is deliberately absent until an observed and calibrated scoring policy exists. */
    public record Evaluation(int policyVersion, FraudEngineStatus status,
                             DeviceRiskEvidence.SourceQuality sourceQuality, EvidenceCoverage evidenceCoverage,
                             List<SignalOutcome> signals, Integer score) {
        public Evaluation {
            if (policyVersion != VERSION || status == null || sourceQuality == null || evidenceCoverage == null
                    || score != null || signals == null || signals.size() != Signal.values().length) {
                throw new IllegalArgumentException("DEVICE_POLICY_EVALUATION");
            }
            signals = List.copyOf(signals);
            for (int index = 0; index < signals.size(); index++) {
                if (signals.get(index).signal() != Signal.values()[index]) {
                    throw new IllegalArgumentException("DEVICE_POLICY_SIGNAL_ORDER");
                }
            }
            boolean hasSkippedSignal = signals.stream()
                    .anyMatch(signal -> signal.status() == FraudEngineStatus.SKIPPED);
            if (hasSkippedSignal
                    && signals.stream().anyMatch(signal -> signal.status() != FraudEngineStatus.SKIPPED)) {
                throw new IllegalArgumentException("DEVICE_POLICY_MIXED_SOURCE");
            }
            boolean sourceIsUntrusted = sourceQuality != DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION;
            if (hasSkippedSignal != sourceIsUntrusted) {
                throw new IllegalArgumentException("DEVICE_POLICY_SOURCE_TRUST_STATE");
            }
            long available = signals.stream().filter(signal -> signal.status() == FraudEngineStatus.AVAILABLE).count();
            FraudEngineStatus expectedStatus = sourceIsUntrusted ? FraudEngineStatus.SKIPPED
                    : available > 0 ? FraudEngineStatus.DEGRADED : FraudEngineStatus.UNAVAILABLE;
            EvidenceCoverage expectedCoverage = available == 2 ? EvidenceCoverage.COMPLETE_SUPPORTED_HISTORY
                    : available == 1 ? EvidenceCoverage.PARTIAL_SUPPORTED_HISTORY
                    : EvidenceCoverage.NO_SUPPORTED_HISTORY;
            if (status != expectedStatus || evidenceCoverage != expectedCoverage) {
                throw new IllegalArgumentException("DEVICE_POLICY_AGGREGATE_STATE");
            }
        }

        public RiskInference riskInference() {
            return RiskInference.NOT_CALIBRATED;
        }
    }

    public Evaluation evaluate(DeviceRiskEvidence evidence) {
        Objects.requireNonNull(evidence, "DEVICE_EVIDENCE_REQUIRED");
        List<SignalOutcome> outcomes = new ArrayList<>(Signal.values().length);
        if (evidence.source().quality() != DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION) {
            for (Signal signal : Signal.values()) {
                outcomes.add(new SignalOutcome(signal, FraudEngineStatus.SKIPPED, null,
                        Reason.UNTRUSTED_SOURCE));
            }
            return new Evaluation(VERSION, FraudEngineStatus.SKIPPED, evidence.source().quality(),
                    EvidenceCoverage.NO_SUPPORTED_HISTORY, outcomes, null);
        }

        boolean completeWindow = evidence.windowStart().equals(evidence.asOf().minus(HISTORY_WINDOW))
                && evidence.coverageThrough() != null && !evidence.coverageThrough().isBefore(evidence.asOf())
                && !evidence.source().observedAt().isAfter(evidence.asOf());
        outcomes.add(history(Signal.DEVICE_NOT_SEEN_RECENTLY, evidence.priorDevice(), completeWindow, true));
        outcomes.add(history(Signal.DEVICE_SWITCH, evidence.switches(), completeWindow, false));
        outcomes.add(unsupported(Signal.OTHER_CUSTOMERS));
        outcomes.add(unsupported(Signal.COUNTRY_MISMATCH));
        outcomes.add(unsupported(Signal.PROXY_OR_VPN));

        long available = outcomes.stream().filter(outcome -> outcome.status() == FraudEngineStatus.AVAILABLE).count();
        EvidenceCoverage coverage = available == 2 ? EvidenceCoverage.COMPLETE_SUPPORTED_HISTORY
                : available == 1 ? EvidenceCoverage.PARTIAL_SUPPORTED_HISTORY
                : EvidenceCoverage.NO_SUPPORTED_HISTORY;
        return new Evaluation(VERSION,
                available > 0 ? FraudEngineStatus.DEGRADED : FraudEngineStatus.UNAVAILABLE,
                evidence.source().quality(), coverage, outcomes, null);
    }

    private static SignalOutcome history(Signal signal, DeviceRiskEvidence.HistoryFact fact,
                                         boolean completeWindow, boolean absencePredicate) {
        if (fact.state() != DeviceRiskEvidence.State.AVAILABLE) {
            Reason reason = switch (fact.state()) {
                case UNTRUSTED -> Reason.UNTRUSTED_SOURCE;
                case INVALID -> Reason.HISTORY_INVALID;
                case UNAVAILABLE -> fact.reason() == DeviceRiskEvidence.Reason.INCOMPLETE_HISTORY
                        ? Reason.HISTORY_INCOMPLETE : Reason.HISTORY_UNAVAILABLE;
                case AVAILABLE -> throw new IllegalStateException("DEVICE_POLICY_FACT_STATE");
            };
            return new SignalOutcome(signal, FraudEngineStatus.UNAVAILABLE, null, reason);
        }
        if (!completeWindow) {
            return new SignalOutcome(signal, FraudEngineStatus.UNAVAILABLE, null,
                    Reason.HISTORY_INCOMPLETE);
        }
        boolean observed = absencePredicate ? fact.count() == 0 : fact.count() > 0;
        Reason reason = absencePredicate
                ? observed ? Reason.NOT_SEEN_IN_WINDOW : Reason.SEEN_IN_WINDOW
                : observed ? Reason.SWITCH_OBSERVED : Reason.NO_SWITCH_OBSERVED;
        return new SignalOutcome(signal, FraudEngineStatus.AVAILABLE, observed, reason);
    }

    private static SignalOutcome unsupported(Signal signal) {
        return new SignalOutcome(signal, FraudEngineStatus.UNAVAILABLE, null,
                Reason.UNSUPPORTED_SOURCE);
    }
}
