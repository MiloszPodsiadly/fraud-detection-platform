package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.artifact.GovernanceArtifactReadException;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceProvider;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceSelector;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceUnavailableException;
import com.frauddetection.alert.governance.exactmodelshadowperformance.VerifiedExactModelShadowPerformanceArtifact;
import com.frauddetection.alert.governance.modelcard.ModelCard;
import com.frauddetection.alert.governance.modelcard.ModelCardProvider;
import com.frauddetection.alert.governance.modelcard.ModelCardProviderUnavailableException;
import com.frauddetection.alert.governance.modelcard.VerifiedModelCardArtifact;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionEvidenceResolution.Reason;

@Service
public class ModelPromotionEvidenceResolver {

    private static final List<String> LIMITATIONS = List.of(
            "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_CERTIFIED_GROUND_TRUTH",
            "BOUNDED_FEEDBACK_POPULATION_DOES_NOT_PROVE_GLOBAL_REPRESENTATIVENESS",
            "NO_EXTERNAL_ATTESTATION",
            "NO_EXTERNAL_IMMUTABLE_STORAGE_GUARANTEE",
            "PROMOTION_POLICY_FRESHNESS_NOT_EVALUATED"
    );

    private final ModelCardProvider modelCardProvider;
    private final ExactModelShadowPerformanceProvider shadowPerformanceProvider;
    private final ObjectMapper objectMapper;

    public ModelPromotionEvidenceResolver(
            ModelCardProvider modelCardProvider,
            ExactModelShadowPerformanceProvider shadowPerformanceProvider,
            ObjectMapper objectMapper
    ) {
        this.modelCardProvider = Objects.requireNonNull(modelCardProvider);
        this.shadowPerformanceProvider = Objects.requireNonNull(shadowPerformanceProvider);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public ModelPromotionEvidenceResolution resolve(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionEvaluationWindow window
    ) {
        Objects.requireNonNull(identity, "identity is required");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(window, "window is required");

        VerifiedModelCardArtifact modelCardArtifact;
        try {
            var resolved = modelCardProvider.findExact(identity);
            if (resolved.isEmpty()) {
                return ModelPromotionEvidenceResolution.notFound(Reason.MODEL_CARD_NOT_FOUND);
            }
            modelCardArtifact = resolved.orElseThrow();
        } catch (ModelCardProviderUnavailableException exception) {
            return ModelPromotionEvidenceResolution.unavailable(Reason.MODEL_CARD_UNAVAILABLE);
        }

        JsonNode modelCardEvaluationEvidence = modelCardArtifact.card().evaluationEvidence();
        if (modelCardEvaluationEvidence == null
                || !"AVAILABLE".equals(optionalText(modelCardEvaluationEvidence, "status"))) {
            return ModelPromotionEvidenceResolution.unavailable(Reason.MODEL_CARD_EVALUATION_UNAVAILABLE);
        }

        ExactModelShadowPerformanceSelector selector = new ExactModelShadowPerformanceSelector(
                identity.modelName(),
                identity.modelVersion(),
                identity.featureContractVersion(),
                identity.modelArtifactSha256(),
                window.fromInclusive(),
                window.toInclusive()
        );
        VerifiedExactModelShadowPerformanceArtifact shadowArtifact;
        try {
            var resolved = shadowPerformanceProvider.findArtifact(selector);
            if (resolved.isEmpty()) {
                return ModelPromotionEvidenceResolution.notFound(Reason.SHADOW_PERFORMANCE_NOT_FOUND);
            }
            shadowArtifact = resolved.orElseThrow();
        } catch (ExactModelShadowPerformanceUnavailableException exception) {
            return ModelPromotionEvidenceResolution.unavailable(Reason.SHADOW_PERFORMANCE_UNAVAILABLE);
        }

        try {
            return ModelPromotionEvidenceResolution.available(bundle(
                    identity, governanceScope, intendedUse, window, modelCardArtifact, shadowArtifact
            ));
        } catch (EvidenceMismatchException | IllegalArgumentException exception) {
            return ModelPromotionEvidenceResolution.unavailable(Reason.EVIDENCE_IDENTITY_OR_WINDOW_MISMATCH);
        } catch (GovernanceArtifactReadException exception) {
            return ModelPromotionEvidenceResolution.unavailable(Reason.EVIDENCE_CANONICALIZATION_UNAVAILABLE);
        }
    }

    private ModelPromotionEvidenceBundle bundle(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionEvaluationWindow window,
            VerifiedModelCardArtifact modelCardArtifact,
            VerifiedExactModelShadowPerformanceArtifact shadowArtifact
    ) {
        ModelCard card = modelCardArtifact.card();
        JsonNode cardEvidence = card.evaluationEvidence();
        require(cardEvidence != null && "AVAILABLE".equals(text(cardEvidence, "status")));
        JsonNode cardEvaluation = card.evaluationSummary();
        JsonNode shadow = shadowArtifact.summary();
        JsonNode shadowEvaluation = object(shadow, "evaluationSummary");
        JsonNode sourceEvaluation = object(shadow, "sourceEvaluation");
        JsonNode cardWindow = object(cardEvaluation, "evaluationWindow");
        require(window.fromInclusive().equals(text(cardWindow, "fromInclusive")));
        require(window.toInclusive().equals(text(cardWindow, "toInclusive")));
        require(cardEvaluation.equals(shadowEvaluation));

        String sourceManifest = text(sourceEvaluation, "manifestSha256");
        String cardSourceManifest = text(cardEvidence, "manifestSha256");
        require(sourceManifest.equals(cardSourceManifest));
        String sourceVerification = text(sourceEvaluation, "sourceDatasetVerificationLevel");
        require(sourceVerification.equals(text(cardEvidence, "sourceDatasetVerificationLevel")));
        require(text(sourceEvaluation, "reportGeneratedAt")
                .equals(text(cardEvidence, "reportGeneratedAt")));

        JsonNode population = object(shadowEvaluation, "population");
        JsonNode coverage = object(shadow, "coverage");
        JsonNode diagnostics = object(shadow, "operationalDiagnostics");
        boolean operationalComplete = available(object(diagnostics, "mlLatencyP95"))
                && available(object(diagnostics, "fallbackRate"))
                && available(object(diagnostics, "mlReasonCodeDistribution"));
        ModelPromotionEvidenceBundle.Content content = new ModelPromotionEvidenceBundle.Content(
                ModelPromotionEvidenceBundle.CURRENT_VERSION,
                identity,
                governanceScope,
                intendedUse,
                window,
                new ModelPromotionEvidenceBundle.ModelCardEvidence(
                        modelCardArtifact.manifestSha256(),
                        card.subject().modelArtifactSha256(),
                        card.generatedAt(),
                        cardSourceManifest,
                        sourceVerification
                ),
                new ModelPromotionEvidenceBundle.ShadowPerformanceEvidence(
                        shadowArtifact.manifestSha256(),
                        text(shadow, "generatedAt"),
                        sourceManifest,
                        text(coverage, "returnedCohortStatus"),
                        text(coverage, "sourcePopulationStatus"),
                        integer(population, "recordsConsidered"),
                        integer(population, "recordsEvaluated"),
                        operationalComplete
                ),
                evaluationEvidence(shadowEvaluation, shadow),
                new ModelPromotionEvidenceBundle.EvidenceTrust(
                        "VERIFIED_REPORTS_AND_MANIFESTS",
                        sourceVerification,
                        text(sourceEvaluation, "externalAttestation"),
                        text(sourceEvaluation, "immutableStorageGuarantee")
                ),
                LIMITATIONS
        );
        return new ModelPromotionEvidenceBundle(content, canonicalSha256(content));
    }

    private ModelPromotionEvidenceBundle.EvaluationEvidence evaluationEvidence(
            JsonNode evaluation,
            JsonNode shadow
    ) {
        JsonNode population = object(evaluation, "population");
        JsonNode classBalance = object(evaluation, "classBalance");
        JsonNode sourceDataset = object(evaluation, "sourceDataset");
        JsonNode metrics = object(evaluation, "supportedMetrics");
        JsonNode mlMetrics = object(metrics, "mlPredictionMetrics");
        JsonNode disagreement = object(metrics, "rulesVsMlDisagreement");
        JsonNode coverage = object(shadow, "coverage");
        JsonNode operational = object(shadow, "operationalDiagnostics");
        JsonNode lineage = object(evaluation, "lineagePolicy");

        Map<Integer, ModelPromotionEvidenceBundle.RankedMetricEvidence> precisionAtBudget = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : object(mlMetrics, "precisionAtK").properties()) {
            JsonNode ranked = entry.getValue();
            JsonNode value = object(ranked, "value");
            int requestedBudget = integer(ranked, "requestedK");
            precisionAtBudget.put(requestedBudget, new ModelPromotionEvidenceBundle.RankedMetricEvidence(
                    booleanValue(value, "available"),
                    requestedBudget,
                    integer(ranked, "actualK"),
                    optionalDecimal(value, "value"),
                    optionalText(value, "reason")
            ));
        }

        JsonNode falsePositiveRate = object(mlMetrics, "falsePositiveRate");
        int falsePositive = integer(mlMetrics, "falsePositive");
        int trueNegative = integer(mlMetrics, "trueNegative");
        JsonNode disagreementRate = object(disagreement, "disagreementRate");
        JsonNode categoryCounts = object(disagreement, "categoryCounts");
        JsonNode latency = object(operational, "mlLatencyP95");
        JsonNode fallback = object(operational, "fallbackRate");

        return new ModelPromotionEvidenceBundle.EvaluationEvidence(
                new ModelPromotionEvidenceBundle.PopulationEvidence(
                        integer(population, "recordsConsidered"),
                        integer(population, "recordsEvaluated"),
                        integer(classBalance, "positiveClassCount"),
                        integer(classBalance, "negativeClassCount"),
                        booleanValue(sourceDataset, "truncated"),
                        text(coverage, "returnedCohortStatus"),
                        text(coverage, "sourcePopulationStatus")
                ),
                precisionAtBudget,
                new ModelPromotionEvidenceBundle.FairPrecisionComparisonEvidence(
                        false, null, null, null, null, null,
                        "MATCHED_RULES_PRECISION_AT_REVIEW_BUDGET_UNAVAILABLE"
                ),
                rateMetric(
                        falsePositiveRate,
                        falsePositive,
                        falsePositive + trueNegative,
                        "ANALYST_FEEDBACK_LABEL"
                ),
                rateMetric(
                        disagreementRate,
                        integer(categoryCounts, "ML_HIGH_RULES_LOW")
                                + integer(categoryCounts, "RULES_HIGH_ML_LOW"),
                        integer(disagreement, "recordsCompared"),
                        "FEEDBACK_CREATED_AT"
                ),
                new ModelPromotionEvidenceBundle.SegmentRegressionEvidence(
                        false, null, null, null, "IDENTIFIABLE_SEGMENT_REGRESSION_EVIDENCE_UNAVAILABLE"
                ),
                new ModelPromotionEvidenceBundle.LatencyEvidence(
                        booleanValue(latency, "available"),
                        optionalDecimal(latency, "valueMilliseconds"),
                        optionalInteger(latency, "sampleSize"),
                        optionalText(latency, "timeBasis"),
                        optionalText(latency, "reason")
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        booleanValue(fallback, "available"),
                        optionalDecimal(fallback, "value"),
                        optionalInteger(fallback, "numerator"),
                        optionalInteger(fallback, "denominator"),
                        optionalText(fallback, "timeBasis"),
                        optionalText(fallback, "reason")
                ),
                new ModelPromotionEvidenceBundle.LineageEvidence(
                        text(lineage, "identityMismatchBehavior"),
                        integer(population, "recordsExcludedIdentityMismatch"),
                        integer(population, "recordsExcludedSourceIdentityMismatch"),
                        integer(population, "recordsExcludedInvalidPredictionEvidence"),
                        integer(population, "recordsExcludedMissingPredictionEvidence"),
                        integer(population, "recordsExcludedUnexpectedMissingPredictionEvidence")
                )
        );
    }

    private ModelPromotionEvidenceBundle.RateMetricEvidence rateMetric(
            JsonNode metric,
            int numerator,
            int denominator,
            String timeBasis
    ) {
        boolean available = booleanValue(metric, "available");
        return new ModelPromotionEvidenceBundle.RateMetricEvidence(
                available,
                optionalDecimal(metric, "value"),
                available ? numerator : null,
                available ? denominator : null,
                available ? timeBasis : null,
                optionalText(metric, "reason")
        );
    }

    private String canonicalSha256(ModelPromotionEvidenceBundle.Content content) {
        return GovernanceCanonicalJson.sha256(objectMapper, content);
    }

    private JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        require(value != null && value.isObject());
        return value;
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        require(value != null && value.isTextual());
        return value.textValue();
    }

    private String optionalText(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private int integer(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }

    private Integer optionalInteger(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        require(value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }

    private BigDecimal optionalDecimal(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        require(value.isNumber());
        return value.decimalValue();
    }

    private boolean booleanValue(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        require(value != null && value.isBoolean());
        return value.booleanValue();
    }

    private boolean available(JsonNode metric) {
        JsonNode value = metric.get("available");
        require(value != null && value.isBoolean());
        return value.booleanValue();
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new EvidenceMismatchException();
        }
    }

    private static final class EvidenceMismatchException extends RuntimeException {
    }
}
