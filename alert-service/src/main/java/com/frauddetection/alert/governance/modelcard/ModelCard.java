package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record ModelCard(
        String schemaVersion,
        String cardType,
        String generatedAt,
        Subject subject,
        String promotionState,
        ModelArtifactVerification modelArtifactVerification,
        JsonNode evaluationEvidence,
        JsonNode evaluationSummary,
        VerifiedUsageBoundary verifiedUsageBoundary
) {

    public record Subject(
            String subjectType,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String modelArtifactSha256,
            String identityCompleteness
    ) {
    }

    public record ModelArtifactVerification(String status, String sha256) {
    }

    public record VerifiedUsageBoundary(
            List<String> allowedModes,
            String runtimeDecisionAuthority,
            String promotionAuthority,
            String thresholdChangeAuthority,
            String paymentAuthorizationAuthority,
            String workflowAuthority
    ) {
    }
}
