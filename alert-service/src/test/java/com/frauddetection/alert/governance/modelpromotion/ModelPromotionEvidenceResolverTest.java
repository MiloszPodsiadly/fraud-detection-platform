package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceProvider;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceUnavailableException;
import com.frauddetection.alert.governance.exactmodelshadowperformance.VerifiedExactModelShadowPerformanceArtifact;
import com.frauddetection.alert.governance.modelcard.ModelCard;
import com.frauddetection.alert.governance.modelcard.ModelCardProvider;
import com.frauddetection.alert.governance.modelcard.ModelCardProviderUnavailableException;
import com.frauddetection.alert.governance.modelcard.VerifiedModelCardArtifact;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.Optional;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionEvidenceResolution.Reason;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionEvidenceResolution.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPromotionEvidenceResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardProvider modelCardProvider = mock(ModelCardProvider.class);
    private final ExactModelShadowPerformanceProvider shadowProvider =
            mock(ExactModelShadowPerformanceProvider.class);
    private final ModelPromotionEvidenceResolver resolver =
            new ModelPromotionEvidenceResolver(modelCardProvider, shadowProvider, objectMapper);

    @Test
    void createsDeterministicBundleOnlyFromExactlyMatchingVerifiedArtifacts() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity identity = identity(shadow);
        ModelPromotionEvaluationWindow window = window(shadow);
        when(modelCardProvider.findExact(identity)).thenReturn(Optional.of(modelCardArtifact(shadow)));
        when(shadowProvider.findArtifact(any())).thenReturn(Optional.of(shadowArtifact(shadow)));

        ModelPromotionEvidenceResolution first = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window
        );
        ModelPromotionEvidenceResolution second = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window
        );

        assertThat(first.status()).isEqualTo(Status.AVAILABLE);
        assertThat(first.reason()).isEqualTo(Reason.NONE);
        assertThat(first.evidenceBundle().canonicalSha256())
                .isEqualTo(second.evidenceBundle().canonicalSha256())
                .matches("^[0-9a-f]{64}$");
        assertThat(first.evidenceBundle().content().artifactIdentity()).isEqualTo(identity);
        assertThat(first.evidenceBundle().content().shadowPerformance().operationalEvidenceComplete()).isFalse();
        assertThat(first.evidenceBundle().content().trust().externalAttestation()).isEqualTo("NOT_PROVIDED");
        assertThat(first.evidenceBundle().content().trust().immutableStorageGuarantee()).isEqualTo("NOT_CLAIMED");
    }

    @Test
    void crossedWindowOrModifiedEvaluationCannotProduceBundle() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity identity = identity(shadow);
        ObjectNode crossed = (ObjectNode) shadow.deepCopy();
        ((ObjectNode) crossed.at("/evaluationSummary/evaluationWindow"))
                .put("fromInclusive", "2026-06-02T00:00:00Z");
        when(modelCardProvider.findExact(identity)).thenReturn(Optional.of(modelCardArtifact(crossed)));
        when(shadowProvider.findArtifact(any())).thenReturn(Optional.of(shadowArtifact(shadow)));

        ModelPromotionEvidenceResolution result = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.EVIDENCE_IDENTITY_OR_WINDOW_MISMATCH);
        assertThat(result.evidenceBundle()).isNull();
    }

    @Test
    void wrongRequestedArtifactShaCannotProduceBundle() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity expectedIdentity = identity(shadow);
        ExactModelArtifactIdentity wrongIdentity = new ExactModelArtifactIdentity(
                expectedIdentity.modelName(),
                expectedIdentity.modelVersion(),
                expectedIdentity.featureContractVersion(),
                "b".repeat(64)
        );
        when(modelCardProvider.findExact(wrongIdentity)).thenReturn(Optional.of(modelCardArtifact(shadow)));
        when(shadowProvider.findArtifact(any())).thenReturn(Optional.of(shadowArtifact(shadow)));

        ModelPromotionEvidenceResolution result = resolver.resolve(
                wrongIdentity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.EVIDENCE_IDENTITY_OR_WINDOW_MISMATCH);
        assertThat(result.evidenceBundle()).isNull();
    }

    @Test
    void zeroRateDenominatorCannotProduceBundle() throws Exception {
        JsonNode shadow = fixture();
        ObjectNode invalid = (ObjectNode) shadow.deepCopy();
        ObjectNode mlMetrics = (ObjectNode) invalid.at("/evaluationSummary/supportedMetrics/mlPredictionMetrics");
        mlMetrics.put("falsePositive", 0);
        mlMetrics.put("trueNegative", 0);
        ExactModelArtifactIdentity identity = identity(invalid);
        when(modelCardProvider.findExact(identity)).thenReturn(Optional.of(modelCardArtifact(invalid)));
        when(shadowProvider.findArtifact(any())).thenReturn(Optional.of(shadowArtifact(invalid)));

        ModelPromotionEvidenceResolution result = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(invalid)
        );

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.EVIDENCE_IDENTITY_OR_WINDOW_MISMATCH);
    }

    @Test
    void missingAndUnavailableProvidersRemainDistinct() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity identity = identity(shadow);
        when(modelCardProvider.findExact(identity)).thenReturn(Optional.empty());

        ModelPromotionEvidenceResolution missing = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(missing.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(missing.reason()).isEqualTo(Reason.MODEL_CARD_NOT_FOUND);

        when(modelCardProvider.findExact(identity)).thenReturn(Optional.of(modelCardArtifact(shadow)));
        when(shadowProvider.findArtifact(any()))
                .thenThrow(new ExactModelShadowPerformanceUnavailableException("private detail"));

        ModelPromotionEvidenceResolution unavailable = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(unavailable.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(unavailable.reason()).isEqualTo(Reason.SHADOW_PERFORMANCE_UNAVAILABLE);
    }

    @Test
    void unavailableModelCardDoesNotFallThroughToOtherEvidence() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity identity = identity(shadow);
        when(modelCardProvider.findExact(identity))
                .thenThrow(new ModelCardProviderUnavailableException("private detail"));

        ModelPromotionEvidenceResolution result = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.MODEL_CARD_UNAVAILABLE);
    }

    @Test
    void unavailableModelCardEvaluationDoesNotResolveShadowEvidence() throws Exception {
        JsonNode shadow = fixture();
        ExactModelArtifactIdentity identity = identity(shadow);
        VerifiedModelCardArtifact artifact = modelCardArtifact(shadow);
        ModelCard card = artifact.card();
        ObjectNode unavailableEvidence = objectMapper.createObjectNode().put("status", "UNAVAILABLE");
        when(modelCardProvider.findExact(identity)).thenReturn(Optional.of(new VerifiedModelCardArtifact(
                new ModelCard(
                        card.schemaVersion(),
                        card.cardType(),
                        card.generatedAt(),
                        card.subject(),
                        card.promotionState(),
                        card.modelArtifactVerification(),
                        unavailableEvidence,
                        card.evaluationSummary(),
                        card.verifiedUsageBoundary()
                ),
                artifact.manifestSha256()
        )));

        ModelPromotionEvidenceResolution result = resolver.resolve(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window(shadow)
        );

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.MODEL_CARD_EVALUATION_UNAVAILABLE);
        verifyNoInteractions(shadowProvider);
    }

    private VerifiedModelCardArtifact modelCardArtifact(JsonNode shadow) {
        JsonNode subject = shadow.at("/evaluationSummary/evaluationSubject");
        JsonNode source = shadow.get("sourceEvaluation");
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("status", "AVAILABLE");
        evidence.put("manifestSha256", source.get("manifestSha256").textValue());
        evidence.put("sourceDatasetVerificationLevel",
                source.get("sourceDatasetVerificationLevel").textValue());
        evidence.put("reportGeneratedAt", source.get("reportGeneratedAt").textValue());
        ModelCard card = new ModelCard(
                "ml-model-card-v1",
                "ML_MODEL_CARD_V1",
                shadow.get("generatedAt").textValue(),
                new ModelCard.Subject(
                        "ML_MODEL",
                        subject.get("modelName").textValue(),
                        subject.get("modelVersion").textValue(),
                        subject.get("featureContractVersion").textValue(),
                        subject.get("modelArtifactSha256").textValue(),
                        "COMPLETE"
                ),
                "NOT_EVALUATED_FOR_PROMOTION",
                new ModelCard.ModelArtifactVerification(
                        "VERIFIED_AGAINST_ARTIFACT_BYTES",
                        subject.get("modelArtifactSha256").textValue()
                ),
                evidence,
                shadow.get("evaluationSummary").deepCopy(),
                null
        );
        return new VerifiedModelCardArtifact(card, "c".repeat(64));
    }

    private VerifiedExactModelShadowPerformanceArtifact shadowArtifact(JsonNode shadow) {
        return new VerifiedExactModelShadowPerformanceArtifact(shadow, "d".repeat(64));
    }

    private ExactModelArtifactIdentity identity(JsonNode shadow) {
        JsonNode subject = shadow.at("/evaluationSummary/evaluationSubject");
        return new ExactModelArtifactIdentity(
                subject.get("modelName").textValue(),
                subject.get("modelVersion").textValue(),
                subject.get("featureContractVersion").textValue(),
                subject.get("modelArtifactSha256").textValue()
        );
    }

    private ModelPromotionEvaluationWindow window(JsonNode shadow) {
        JsonNode window = shadow.at("/evaluationSummary/evaluationWindow");
        return new ModelPromotionEvaluationWindow(
                window.get("fromInclusive").textValue(),
                window.get("toInclusive").textValue()
        );
    }

    private ModelGovernanceScope scope() {
        return new ModelGovernanceScope(
                ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.PRODUCTION
        );
    }

    private JsonNode fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/fixtures/exact-model-shadow-performance/python-generated-v1/"
                        + "exact_model_shadow_performance.json"
        )) {
            return objectMapper.readTree(stream);
        }
    }
}
