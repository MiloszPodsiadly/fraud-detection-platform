package com.frauddetection.scoring.device;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;

import java.util.List;
import java.util.Objects;

/** Maps pure policy outcomes to the existing bounded engine diagnostic envelope. */
public final class DeviceRiskSignalEvaluationMapper {
    private static final String EVIDENCE_SOURCE = "DEVICE_RISK";

    public FraudSignalEvaluation map(DeviceRiskSignalPolicy.Evaluation evaluation) {
        Objects.requireNonNull(evaluation, "DEVICE_POLICY_EVALUATION_REQUIRED");
        List<String> reasonCodes = evaluation.signals().stream()
                .map(DeviceRiskSignalPolicy.SignalOutcome::reasonCode)
                .toList();
        List<FraudEngineEvidence> evidence = evaluation.signals().stream()
                .map(this::evidence)
                .toList();
        String statusReason = switch (evaluation.status()) {
            case DEGRADED -> DeviceRiskSignalReasonCode.DEVICE_SCORE_NOT_CALIBRATED.wireValue();
            case SKIPPED -> DeviceRiskSignalReasonCode.DEVICE_EVIDENCE_UNTRUSTED.wireValue();
            case UNAVAILABLE -> evaluation.signals().stream()
                    .allMatch(outcome -> outcome.support() == DeviceRiskSignalPolicy.Support.UNSUPPORTED)
                    ? DeviceRiskSignalReasonCode.DEVICE_SIGNALS_UNSUPPORTED.wireValue()
                    : DeviceRiskSignalReasonCode.DEVICE_EVIDENCE_UNAVAILABLE.wireValue();
            default -> throw new IllegalArgumentException("DEVICE_POLICY_OPERATING_STATUS_INVALID");
        };
        return new FraudSignalEvaluation(evaluation.status(), null, null, FraudEngineConfidence.UNKNOWN,
                reasonCodes, List.of(), evidence, null, null, null, statusReason);
    }

    private FraudEngineEvidence evidence(DeviceRiskSignalPolicy.SignalOutcome outcome) {
        return new FraudEngineEvidence(FraudEngineEvidenceType.DEVICE_SIGNAL, outcome.reasonCode(),
                title(outcome.signal()), description(outcome), EVIDENCE_SOURCE, evidenceStatus(outcome));
    }

    private FraudEngineEvidenceStatus evidenceStatus(DeviceRiskSignalPolicy.SignalOutcome outcome) {
        return switch (outcome.evidenceState()) {
            case AVAILABLE -> FraudEngineEvidenceStatus.AVAILABLE;
            case UNAVAILABLE, UNSUPPORTED, INVALID -> FraudEngineEvidenceStatus.UNAVAILABLE;
            case UNTRUSTED -> FraudEngineEvidenceStatus.PARTIAL;
        };
    }

    private static String title(DeviceRiskSignalPolicy.Signal signal) {
        return switch (signal) {
            case DEVICE_NOT_SEEN_RECENTLY -> "Recent device history";
            case DEVICE_SWITCH -> "Device switch";
            case OTHER_CUSTOMERS -> "Shared device";
            case COUNTRY_MISMATCH -> "Device country";
            case PROXY_OR_VPN -> "Network detector";
        };
    }

    private static String description(DeviceRiskSignalPolicy.SignalOutcome outcome) {
        return switch (outcome.reason()) {
            case NOT_SEEN_IN_WINDOW -> "No matching scoped device observation exists in the complete prior 30-day window.";
            case SEEN_IN_WINDOW -> "A matching scoped device observation exists in the complete prior 30-day window.";
            case SWITCH_OBSERVED -> "A change from the last prior device to this event was observed.";
            case NO_SWITCH_OBSERVED -> "No device change was observed for this event.";
            case HISTORY_INCOMPLETE -> "The complete prior 30-day history is not available.";
            case HISTORY_UNAVAILABLE -> "Required history is missing or unavailable for this scope.";
            case HISTORY_INVALID -> "History failed provenance or time validation.";
            case UNTRUSTED_SOURCE -> "The evidence source is not trusted for this evaluation.";
            case UNSUPPORTED_SOURCE -> "No trusted provider is defined for this signal.";
        };
    }
}
