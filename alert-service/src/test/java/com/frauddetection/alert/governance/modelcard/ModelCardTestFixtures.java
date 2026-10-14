package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

final class ModelCardTestFixtures {

    static final String GENERATED_AT = "2026-10-13T10:15:30Z";
    static final String ARTIFACT_SHA = "a".repeat(64);

    private ModelCardTestFixtures() {
    }

    static ModelCard validUnavailableCard(ObjectMapper objectMapper) {
        return new ModelCard(
                "ml-model-card-v1",
                "ML_MODEL_CARD_V1",
                GENERATED_AT,
                new ModelCard.Subject(
                        "ML_MODEL",
                        "fraud_logistic",
                        "2026.10.13",
                        "fraud-features-v2",
                        ARTIFACT_SHA,
                        "COMPLETE"
                ),
                "NOT_EVALUATED_FOR_PROMOTION",
                new ModelCard.ModelArtifactVerification("VERIFIED_AGAINST_ARTIFACT_BYTES", ARTIFACT_SHA),
                objectMapper.createObjectNode()
                        .put("status", "NOT_AVAILABLE")
                        .put("reason", "MODEL_EVALUATION_NOT_CONFIGURED"),
                null,
                new ModelCard.VerifiedUsageBoundary(
                        List.of("COMPARE", "OFFLINE_EVALUATION", "SHADOW"),
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE"
                )
        );
    }
}
