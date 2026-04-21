package com.frauddetection.alert.assistant;

import java.util.Map;

public record FraudReasonSummary(
        String reasonCode,
        String analystLabel,
        String explanation,
        Double contribution,
        Map<String, Object> evidence
) {
}
