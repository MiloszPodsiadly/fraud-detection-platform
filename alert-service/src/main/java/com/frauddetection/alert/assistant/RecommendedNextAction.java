package com.frauddetection.alert.assistant;

import java.util.List;

public record RecommendedNextAction(
        String actionCode,
        String title,
        String rationale,
        List<String> suggestedReviewSteps
) {
}
