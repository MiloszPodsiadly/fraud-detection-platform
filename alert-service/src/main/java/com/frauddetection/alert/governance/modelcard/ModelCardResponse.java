package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record ModelCardResponse(
        String schemaVersion,
        String cardType,
        String generatedAt,
        ModelCard.Subject subject,
        String promotionState,
        ModelCard.ModelArtifactVerification modelArtifactVerification,
        JsonNode evaluationEvidence,
        JsonNode evaluationSummary,
        ModelCard.VerifiedUsageBoundary verifiedUsageBoundary
) {

    static ModelCardResponse from(ModelCard card) {
        return new ModelCardResponse(
                card.schemaVersion(),
                card.cardType(),
                card.generatedAt(),
                card.subject(),
                card.promotionState(),
                card.modelArtifactVerification(),
                card.evaluationEvidence().deepCopy(),
                card.evaluationSummary() == null ? null : card.evaluationSummary().deepCopy(),
                new ModelCard.VerifiedUsageBoundary(
                        List.copyOf(card.verifiedUsageBoundary().allowedModes()),
                        List.copyOf(card.verifiedUsageBoundary().intendedUse()),
                        List.copyOf(card.verifiedUsageBoundary().notIntendedUse()),
                        List.copyOf(card.verifiedUsageBoundary().useLimitations()),
                        card.verifiedUsageBoundary().runtimeDecisionAuthority(),
                        card.verifiedUsageBoundary().promotionAuthority(),
                        card.verifiedUsageBoundary().thresholdChangeAuthority(),
                        card.verifiedUsageBoundary().paymentAuthorizationAuthority(),
                        card.verifiedUsageBoundary().workflowAuthority()
                )
        );
    }
}
