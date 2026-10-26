package com.frauddetection.alert.governance.exactmodelshadowperformance;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

public record VerifiedExactModelShadowPerformanceArtifact(JsonNode summary, String manifestSha256) {

    public VerifiedExactModelShadowPerformanceArtifact {
        Objects.requireNonNull(summary, "summary is required");
        if (manifestSha256 == null || !manifestSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("manifestSha256 must be lowercase SHA-256");
        }
        summary = summary.deepCopy();
    }

    @Override
    public JsonNode summary() {
        return summary.deepCopy();
    }
}
