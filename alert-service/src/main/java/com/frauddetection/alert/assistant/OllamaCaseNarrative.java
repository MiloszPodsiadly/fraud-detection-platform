package com.frauddetection.alert.assistant;

import java.util.List;

public record OllamaCaseNarrative(
        String overview,
        List<String> keyObservations,
        String recommendedActionRationale,
        String uncertainty,
        String modelName,
        String modelVersion
) {
}
