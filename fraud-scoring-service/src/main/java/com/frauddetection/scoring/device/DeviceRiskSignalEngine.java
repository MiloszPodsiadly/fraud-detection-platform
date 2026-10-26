package com.frauddetection.scoring.device;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;

import java.util.List;
import java.util.Objects;

/** Internal candidate only. No production reader, Spring bean, registry entry or public mapping exists yet. */
public final class DeviceRiskSignalEngine implements FraudSignalEngine {
    public static final String ENGINE_ID = "device.primary";
    private static final String VERSION = "device-v1";
    private static final String NOT_ADMITTED = "DEVICE_EVIDENCE_NOT_ADMITTED";
    private static final String SCORE_NOT_CALIBRATED = "DEVICE_SCORE_NOT_CALIBRATED";
    private static final String PARTIAL_EVIDENCE = "DEVICE_EVIDENCE_PARTIAL";
    private static final String UNAVAILABLE_EVIDENCE = "DEVICE_EVIDENCE_UNAVAILABLE";
    private static final String UNTRUSTED_EVIDENCE = "DEVICE_EVIDENCE_UNTRUSTED";

    private final AdmittedDeviceRiskEvidenceReader reader;
    private final DeviceRiskSignalPolicy policy;

    public DeviceRiskSignalEngine(AdmittedDeviceRiskEvidenceReader reader, DeviceRiskSignalPolicy policy) {
        this.reader = Objects.requireNonNull(reader, "reader is required");
        this.policy = Objects.requireNonNull(policy, "policy is required");
    }

    @Override
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        var evidence = Objects.requireNonNull(reader.read(context), "DEVICE_EVIDENCE_READ_RESULT_REQUIRED");
        if (evidence.isEmpty()) {
            return result(FraudEngineStatus.UNAVAILABLE, FraudEngineConfidence.UNKNOWN, NOT_ADMITTED);
        }
        var outcome = policy.evaluate(evidence.get());
        return switch (outcome.status()) {
            case AVAILABLE -> result(FraudEngineStatus.DEGRADED, outcome.confidence(), SCORE_NOT_CALIBRATED);
            case DEGRADED -> result(FraudEngineStatus.DEGRADED, outcome.confidence(), PARTIAL_EVIDENCE);
            case UNAVAILABLE -> result(FraudEngineStatus.UNAVAILABLE, FraudEngineConfidence.UNKNOWN, UNAVAILABLE_EVIDENCE);
            case SKIPPED -> result(FraudEngineStatus.SKIPPED, FraudEngineConfidence.UNKNOWN, UNTRUSTED_EVIDENCE);
            default -> throw new IllegalStateException("DEVICE_POLICY_UNEXPECTED_STATUS");
        };
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.DEVICE_RISK, "java", VERSION, false);
    }

    private static FraudSignalEvaluation result(FraudEngineStatus status, FraudEngineConfidence confidence,
                                                String statusReason) {
        return new FraudSignalEvaluation(status, null, null, confidence,
                List.of(), List.of(), List.of(), null, null, null, statusReason);
    }
}
