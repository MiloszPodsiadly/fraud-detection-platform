package com.frauddetection.scoring.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.scoring.context.ScoringContext;

import java.util.Optional;

/** Future boundary: return only evidence authenticated and bound to this scoring occurrence. */
@FunctionalInterface
public interface AdmittedDeviceRiskEvidenceReader {
    Optional<DeviceRiskEvidence> read(ScoringContext context);
}
