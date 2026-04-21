package com.frauddetection.alert.assistant;

import java.util.List;
import java.util.Map;

public record AnalystCaseSummaryRequest(
        String alertId,
        String analystId,
        List<String> focusAreas,
        Map<String, Object> context
) {
}
