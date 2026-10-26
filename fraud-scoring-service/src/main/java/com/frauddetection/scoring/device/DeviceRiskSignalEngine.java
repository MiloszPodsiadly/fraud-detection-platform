package com.frauddetection.scoring.device;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;

import java.util.List;
import java.util.Objects;

/** Disabled candidate. No occurrence authority is available to bind evidence to this scoring context. */
public final class DeviceRiskSignalEngine implements FraudSignalEngine {
    public static final String ENGINE_ID = "device.primary";
    private static final String VERSION = "device-v1";
    private static final DeviceRiskSignalReasonCode NOT_ADMITTED = DeviceRiskSignalReasonCode.DEVICE_EVIDENCE_NOT_ADMITTED;

    @Override
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        // ScoringContext contains no independently authenticated institution/occurrence binding.
        // Do not read Optional<DeviceRiskEvidence> or infer trust from transaction/correlation fields.
        return new FraudSignalEvaluation(FraudEngineStatus.UNAVAILABLE, null, null, FraudEngineConfidence.UNKNOWN,
                List.of(NOT_ADMITTED.wireValue()), List.of(),
                List.of(new FraudEngineEvidence(FraudEngineEvidenceType.DEVICE_SIGNAL, NOT_ADMITTED.wireValue(),
                        "Device evidence admission", "No independently verified occurrence binding was supplied.",
                        "DEVICE_RISK", FraudEngineEvidenceStatus.UNAVAILABLE)),
                null, null, null, NOT_ADMITTED.wireValue());
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.DEVICE_RISK, "java", VERSION, false);
    }

}
