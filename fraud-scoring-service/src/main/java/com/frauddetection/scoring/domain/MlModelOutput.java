package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MlModelOutput(
        boolean available,
        Double fraudScore,
        RiskLevel riskLevel,
        String modelName,
        String modelVersion,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        Map<String, Object> explanationMetadata,
        String fallbackReason
) {
}
