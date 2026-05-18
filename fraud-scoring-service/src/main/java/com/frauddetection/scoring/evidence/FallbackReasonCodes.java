package com.frauddetection.scoring.evidence;

import java.util.Locale;

public final class FallbackReasonCodes {

    private FallbackReasonCodes() {
    }

    public static String from(String fallbackReason) {
        if (fallbackReason == null || fallbackReason.isBlank()) {
            return "unknown_fallback_reason";
        }
        String normalized = fallbackReason.toLowerCase(Locale.ROOT);
        if (normalized.contains("request failed")) {
            return "ml_request_failed";
        }
        if (normalized.contains("invalid") || normalized.contains("empty response")) {
            return "ml_response_invalid";
        }
        if (normalized.contains("runtime")) {
            return "ml_runtime_unavailable";
        }
        if (normalized.contains("unavailable") || normalized.contains("not configured")) {
            return "ml_model_unavailable";
        }
        if (normalized.contains("fallback")) {
            return "scoring_fallback_used";
        }
        return "unknown_fallback_reason";
    }
}
