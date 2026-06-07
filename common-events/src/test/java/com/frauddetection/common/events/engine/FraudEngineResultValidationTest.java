package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineResultValidationTest {

    @Test
    void requiredIdentityFieldsAreValidated() {
        assertThatThrownBy(() -> result(null, FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM))
                .hasMessageContaining("engineId");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", null, "java", FraudEngineStatus.AVAILABLE,
                0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(),
                null, null, null, null, now()))
                .hasMessageContaining("engineType");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, null,
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM,
                List.of(), List.of(), List.of(), null, null, null, null, now()))
                .hasMessageContaining("engineLanguage");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java", null,
                0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(),
                null, null, null, null, now()))
                .hasMessageContaining("status");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM,
                List.of(), List.of(), List.of(), null, null, null, null, null))
                .hasMessageContaining("generatedAt");
    }

    @Test
    void engineLanguageIsBoundedToRuntimeNamesUsedByEngines() {
        assertThat(result(FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM).engineLanguage())
                .isEqualTo("java");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "JAVA",
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM,
                List.of(), List.of(), List.of(), null, null, null, null, now()))
                .hasMessageContaining("engineLanguage");
    }

    @Test
    void scoreRangeScaleAndMissingScoreAreValidated() {
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, Double.NaN, RiskLevel.MEDIUM))
                .hasMessageContaining("score must be finite");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, Double.POSITIVE_INFINITY, RiskLevel.MEDIUM))
                .hasMessageContaining("score must be finite");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, Double.NEGATIVE_INFINITY, RiskLevel.MEDIUM))
                .hasMessageContaining("score must be finite");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, -0.0001d, RiskLevel.MEDIUM))
                .hasMessageContaining("score");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 1.0001d, RiskLevel.MEDIUM))
                .hasMessageContaining("score");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.12345d, RiskLevel.MEDIUM))
                .hasMessageContaining("score scale");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, null, RiskLevel.MEDIUM))
                .hasMessageContaining("requires score");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.4000d, null))
                .hasMessageContaining("requires riskLevel");

        FraudEngineResult unavailable = operational(FraudEngineStatus.UNAVAILABLE, null);
        assertThat(unavailable.score()).isNull();
        assertThat(unavailable.riskLevel()).isNull();
    }

    @Test
    void availableRequiresKnownConfidence() {
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, null, List.of(), List.of(), List.of(),
                3L, null, null, null, now()))
                .hasMessageContaining("requires confidence");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.UNKNOWN,
                List.of(), List.of(), List.of(), 3L, null, null, null, now()))
                .hasMessageContaining("known confidence");

        for (FraudEngineConfidence confidence : List.of(FraudEngineConfidence.LOW, FraudEngineConfidence.MEDIUM,
                FraudEngineConfidence.HIGH)) {
            FraudEngineResult result = new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                    FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, confidence, List.of(), List.of(),
                    List.of(), 3L, null, null, null, now());

            assertThat(result.confidence()).isEqualTo(confidence);
        }
    }

    @Test
    void availableRejectsStatusReason() {
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.AVAILABLE, 0.4000d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM,
                List.of("HIGH_VELOCITY"), List.of(), List.of(), 3L, null, null,
                "ENGINE_STATUS", now()))
                .hasMessageContaining("must not declare statusReason");
    }

    @Test
    void latencyIsBounded() {
        assertThatThrownBy(() -> resultWithLatency(-1L)).hasMessageContaining("latencyMs");
        assertThatThrownBy(() -> resultWithLatency(300_001L)).hasMessageContaining("latencyMs");
    }

    @Test
    void operationalStatusesDoNotCarryScoreOrRiskAndDefaultUnknownConfidence() {
        for (FraudEngineStatus status : List.of(FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.TIMEOUT,
                FraudEngineStatus.SKIPPED)) {
            FraudEngineResult result = operational(status, null);

            assertThat(result.score()).isNull();
            assertThat(result.riskLevel()).isNull();
            assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        }
    }

    @Test
    void operationalStatusesRejectScoreRiskAndKnownConfidence() {
        assertThatThrownBy(() -> result(FraudEngineStatus.UNAVAILABLE, 0.1000d, null))
                .hasMessageContaining("must not declare score");
        assertThatThrownBy(() -> operational(FraudEngineStatus.TIMEOUT, RiskLevel.LOW))
                .hasMessageContaining("must not declare riskLevel");
        assertThatThrownBy(() -> new FraudEngineResult("ml.python.primary", FraudEngineType.ML_MODEL, "python",
                FraudEngineStatus.SKIPPED, null, null, FraudEngineConfidence.LOW, List.of("ENGINE_SKIPPED"),
                List.of(), List.of(), 3L, null, null, "ENGINE_SKIPPED", now()))
                .hasMessageContaining("UNKNOWN confidence");
    }

    @Test
    void nonAvailableStatusesRequireStatusReason() {
        for (FraudEngineStatus status : List.of(FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.TIMEOUT,
                FraudEngineStatus.SKIPPED, FraudEngineStatus.DEGRADED, FraudEngineStatus.FALLBACK_USED)) {
            assertThatThrownBy(() -> nonAvailableWithoutStatusReason(status))
                    .as(status.name())
                    .hasMessageContaining("statusReason");
        }
    }

    @Test
    void fallbackAndDegradedScoreRiskArePaired() {
        assertThat(fallback(0.5000d, RiskLevel.MEDIUM).score()).isEqualTo(0.5d);
        assertThatThrownBy(() -> fallback(0.5000d, null)).hasMessageContaining("provided together");
        assertThatThrownBy(() -> fallback(null, RiskLevel.MEDIUM)).hasMessageContaining("provided together");

        assertThat(degraded(0.5000d, RiskLevel.MEDIUM).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThatThrownBy(() -> degraded(0.5000d, null)).hasMessageContaining("provided together");
        assertThatThrownBy(() -> degraded(null, RiskLevel.MEDIUM)).hasMessageContaining("provided together");
    }

    @Test
    void fallbackAndDegradedRejectHighConfidence() {
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.FALLBACK_USED, 0.5000d, RiskLevel.MEDIUM, FraudEngineConfidence.HIGH,
                List.of("RULE_ENGINE_FALLBACK"), List.of(), List.of(), 3L, null, null,
                "RULE_ENGINE_FALLBACK", now()))
                .hasMessageContaining("HIGH confidence");
        assertThatThrownBy(() -> new FraudEngineResult("rules.primary", FraudEngineType.RULES, "java",
                FraudEngineStatus.DEGRADED, 0.5000d, RiskLevel.MEDIUM, FraudEngineConfidence.HIGH,
                List.of("PARTIAL_CONTEXT"), List.of(), List.of(), 3L, null, null, "PARTIAL_CONTEXT", now()))
                .hasMessageContaining("HIGH confidence");
    }

    @Test
    void boundedCollectionsAreCopiedAndLimited() {
        FraudEngineResult result = resultWithLists(null, null, null);

        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.contributions()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThatThrownBy(() -> resultWithLists(Collections.nCopies(11, "HIGH_VELOCITY"), List.of(), List.of()))
                .hasMessageContaining("reasonCodes");
        assertThatThrownBy(() -> resultWithLists(List.of(), Collections.nCopies(11, contribution()), List.of()))
                .hasMessageContaining("contributions");
        assertThatThrownBy(() -> resultWithLists(List.of(), List.of(), Collections.nCopies(11, evidence())))
                .hasMessageContaining("evidence");
    }

    @Test
    void identifiersAndModelFieldsAreBounded() {
        assertThatThrownBy(() -> resultWithLists(List.of("A".repeat(65)), List.of(), List.of()))
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> resultWithModel("a".repeat(65), null, null))
                .hasMessageContaining("modelName");
        assertThatThrownBy(() -> resultWithModel(null, "v".repeat(65), null))
                .hasMessageContaining("modelVersion");
        assertThatThrownBy(() -> resultWithModel(null, null, "A".repeat(129)))
                .hasMessageContaining("statusReason");
        assertThatThrownBy(() -> result("rules\nprimary", FraudEngineStatus.AVAILABLE, 0.4000d,
                RiskLevel.MEDIUM)).hasMessageContaining("control characters");
    }

    @Test
    void contributionValidationIsBoundedStrictAndCompatible() {
        FraudEngineContribution contribution = contribution();

        assertThat(contribution.feature()).isEqualTo("TRANSFER_COUNT");
        assertThat(contribution.featureCode()).isEqualTo("TRANSFER_COUNT");
        assertThat(contribution.valueBucket()).isEqualTo("HIGH");
        assertThatThrownBy(() -> new FraudEngineContribution(null, null, null,
                FraudEngineContributionDirection.NEUTRAL)).hasMessageContaining("feature");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, null, null))
                .hasMessageContaining("direction");
        assertThatThrownBy(() -> new FraudEngineContribution("transfer_count", null, null,
                FraudEngineContributionDirection.NEUTRAL)).hasMessageContaining("UPPER_SNAKE");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, Double.NaN,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight must be finite");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, Double.POSITIVE_INFINITY,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight must be finite");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, Double.NEGATIVE_INFINITY,
                FraudEngineContributionDirection.DECREASES_RISK)).hasMessageContaining("weight must be finite");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, -1.0001d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 1.0001d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 0.12345d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight scale");
    }

    @Test
    void contributionDirectionAndWeightCannotContradict() {
        assertThat(new FraudEngineContribution("TRANSFER_COUNT", null, 0.2000d,
                FraudEngineContributionDirection.INCREASES_RISK).weight()).isEqualTo(0.2d);
        assertThat(new FraudEngineContribution("TRANSFER_COUNT", null, -0.2000d,
                FraudEngineContributionDirection.DECREASES_RISK).weight()).isEqualTo(-0.2d);
        assertThat(new FraudEngineContribution("TRANSFER_COUNT", null, 0.0d,
                FraudEngineContributionDirection.NEUTRAL).weight()).isEqualTo(0.0d);
        assertThat(new FraudEngineContribution("TRANSFER_COUNT", null, null,
                FraudEngineContributionDirection.UNKNOWN).weight()).isNull();

        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, -0.2000d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("INCREASES_RISK");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 0.2000d,
                FraudEngineContributionDirection.DECREASES_RISK)).hasMessageContaining("DECREASES_RISK");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 0.2000d,
                FraudEngineContributionDirection.NEUTRAL)).hasMessageContaining("NEUTRAL");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 0.0d,
                FraudEngineContributionDirection.UNKNOWN)).hasMessageContaining("UNKNOWN");
    }

    @Test
    void evidenceValidationIsBoundedStrictAndCompatible() {
        FraudEngineEvidence evidence = evidence();

        assertThat(evidence.reasonCode()).isEqualTo("HIGH_VELOCITY");
        assertThat(evidence.evidenceCode()).isEqualTo("HIGH_VELOCITY");
        assertThat(evidence.title()).isEqualTo("High velocity");
        assertThat(evidence.description()).isEqualTo("Bounded rule scoring signal.");
        assertThatThrownBy(() -> new FraudEngineEvidence(null, "HIGH_VELOCITY", "High velocity",
                null, "RULES", FraudEngineEvidenceStatus.AVAILABLE)).hasMessageContaining("evidenceType");
        assertThat(new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, null,
                "High velocity", null, "RULES", FraudEngineEvidenceStatus.AVAILABLE).reasonCode())
                .isNull();
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "high_velocity",
                "High velocity", null, "RULES", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("UPPER_SNAKE");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                "High velocity", null, null, FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                "High velocity", null, "RULES", null)).hasMessageContaining("status");
    }

    @Test
    void forbiddenRawPiiDecisioningAndTrainingTermsAreRejectedAtContributionConstruction() {
        for (String unsafe : List.of("RAW_PAYLOAD", "FEATURE_VECTOR", "STACK_TRACE", "TOKEN", "SECRET",
                "ENDPOINT", "CUSTOMER_ID", "ACCOUNT_ID", "CARD_ID", "DEVICE_ID", "MERCHANT_ID",
                "GROUND_TRUTH", "TRAINING_LABEL", "FINAL_DECISION", "RECOMMENDED_ACTION",
                "PAYMENT_AUTHORIZATION", "RULE_UPDATE")) {
            assertThatThrownBy(() -> new FraudEngineContribution(unsafe, null, null,
                    FraudEngineContributionDirection.NEUTRAL))
                    .as(unsafe)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    void contributionValueRejectsUnsafeTextChannelsAndAcceptsSafeBuckets() {
        for (String unsafe : unsafeTextTerms()) {
            assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", unsafe, null,
                    FraudEngineContributionDirection.NEUTRAL))
                    .as(unsafe)
                    .hasMessageContaining("forbidden");
        }

        for (String bucket : List.of("HIGH", "MEDIUM", "LOW", "ELEVATED", "UNKNOWN")) {
            assertThat(new FraudEngineContribution("TRANSFER_COUNT", bucket, null,
                    FraudEngineContributionDirection.NEUTRAL).value())
                    .as(bucket)
                    .isEqualTo(bucket);
        }
    }

    @Test
    void evidenceReasonCodeRejectsForbiddenTermsAtConstruction() {
        for (String unsafe : List.of("RAW_EVIDENCE", "STACK_TRACE", "TOKEN", "SECRET", "ENDPOINT")) {
            assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, unsafe,
                    "High velocity", null, "RULES", FraudEngineEvidenceStatus.AVAILABLE))
                    .as(unsafe)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    void evidenceTitleAndDescriptionRejectUnsafeTerms() {
        for (String unsafe : unsafeTextTerms()) {
            assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                    unsafe, null, "RULES", FraudEngineEvidenceStatus.AVAILABLE))
                    .as("title " + unsafe)
                    .hasMessageContaining("forbidden");
            assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                    "High velocity", unsafe, "RULES", FraudEngineEvidenceStatus.AVAILABLE))
                    .as("description " + unsafe)
                    .hasMessageContaining("forbidden");
        }

        FraudEngineEvidence evidence = new FraudEngineEvidence(
                FraudEngineEvidenceType.RULE_MATCH,
                "HIGH_VELOCITY",
                "High velocity summary",
                "Bounded display summary",
                "RULES",
                FraudEngineEvidenceStatus.AVAILABLE
        );
        assertThat(evidence.title()).isEqualTo("High velocity summary");
        assertThat(evidence.description()).isEqualTo("Bounded display summary");
    }

    @Test
    void legacyMetadataReasonCodesAreNarrowMachineCodeExceptions() {
        for (String reasonCode : List.of(
                "ML_AVAILABILITY_METADATA_MISSING",
                "ML_AVAILABILITY_METADATA_INVALID",
                "ML_MODEL_METADATA_MISSING"
        )) {
            assertThat(resultWithLists(List.of(reasonCode), List.of(), List.of()).reasonCodes())
                    .as(reasonCode)
                    .containsExactly(reasonCode);
            assertThat(new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, reasonCode,
                    "Model availability", "Bounded display summary", "ML_MODEL",
                    FraudEngineEvidenceStatus.AVAILABLE).reasonCode())
                    .as(reasonCode)
                    .isEqualTo(reasonCode);
            assertThat(fallbackWithStatusReason(reasonCode).statusReason())
                    .as(reasonCode)
                    .isEqualTo(reasonCode);
        }

        for (String unsafe : List.of(
                "RAW_METADATA",
                "CUSTOMER_METADATA",
                "METADATA_PAYLOAD",
                "ARBITRARY_METADATA",
                "MODEL_METADATA_DUMP"
        )) {
            assertThatThrownBy(() -> resultWithLists(List.of(unsafe), List.of(), List.of()))
                    .as("reasonCodes " + unsafe)
                    .hasMessageContaining("forbidden");
            assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, unsafe,
                    "Model availability", "Bounded display summary", "ML_MODEL",
                    FraudEngineEvidenceStatus.AVAILABLE))
                    .as("evidence reasonCode " + unsafe)
                    .hasMessageContaining("forbidden");
            assertThatThrownBy(() -> fallbackWithStatusReason(unsafe))
                    .as("statusReason " + unsafe)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    void noUnboundedContractTypesAreIntroduced() {
        List<String> componentNames = List.of(FraudEngineResult.class, FraudEngineContribution.class,
                        FraudEngineEvidence.class).stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).doesNotContain("metadata");
        assertThat(contractFieldTypes()).doesNotContain(Map.class, Object.class);
        assertThat(contractFieldTypes())
                .noneMatch(type -> type.getName().equals("com.fasterxml.jackson.databind.JsonNode"));
    }

    private List<Class<?>> contractFieldTypes() {
        return List.of(FraudEngineResult.class, FraudEngineContribution.class, FraudEngineEvidence.class).stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getType)
                .toList();
    }

    private FraudEngineResult result(FraudEngineStatus status, Double score, RiskLevel riskLevel) {
        return result("rules.primary", status, score, riskLevel);
    }

    private FraudEngineResult result(String engineId, FraudEngineStatus status, Double score, RiskLevel riskLevel) {
        return new FraudEngineResult(
                engineId,
                FraudEngineType.RULES,
                "java",
                status,
                score,
                riskLevel,
                status == FraudEngineStatus.AVAILABLE ? FraudEngineConfidence.MEDIUM : FraudEngineConfidence.UNKNOWN,
                List.of(status == FraudEngineStatus.AVAILABLE ? "HIGH_VELOCITY" : "ENGINE_STATUS"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                status == FraudEngineStatus.AVAILABLE ? null : "ENGINE_STATUS",
                now()
        );
    }

    private FraudEngineResult operational(FraudEngineStatus status, RiskLevel riskLevel) {
        return new FraudEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                "python",
                status,
                null,
                riskLevel,
                null,
                List.of("ENGINE_STATUS"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                "ENGINE_STATUS",
                now()
        );
    }

    private FraudEngineResult nonAvailableWithoutStatusReason(FraudEngineStatus status) {
        return new FraudEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                "python",
                status,
                null,
                null,
                null,
                List.of("ENGINE_STATUS"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                null,
                now()
        );
    }

    private FraudEngineResult fallback(Double score, RiskLevel riskLevel) {
        return fallbackWithStatusReason(score, riskLevel, "RULE_ENGINE_FALLBACK");
    }

    private FraudEngineResult fallbackWithStatusReason(String statusReason) {
        return fallbackWithStatusReason(0.5000d, RiskLevel.MEDIUM, statusReason);
    }

    private FraudEngineResult fallbackWithStatusReason(Double score, RiskLevel riskLevel, String statusReason) {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.FALLBACK_USED,
                score,
                riskLevel,
                FraudEngineConfidence.UNKNOWN,
                List.of("FALLBACK_PATH_USED"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                statusReason,
                now()
        );
    }

    private FraudEngineResult degraded(Double score, RiskLevel riskLevel) {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.DEGRADED,
                score,
                riskLevel,
                FraudEngineConfidence.LOW,
                List.of("PARTIAL_CONTEXT"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                "PARTIAL_CONTEXT",
                now()
        );
    }

    private FraudEngineResult resultWithLatency(Long latencyMs) {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.4000d,
                RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM,
                List.of("HIGH_VELOCITY"),
                List.of(),
                List.of(),
                latencyMs,
                null,
                null,
                null,
                now()
        );
    }

    private FraudEngineResult resultWithLists(
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence
    ) {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.4000d,
                RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM,
                reasonCodes,
                contributions,
                evidence,
                3L,
                null,
                null,
                null,
                now()
        );
    }

    private FraudEngineResult resultWithModel(String modelName, String modelVersion, String statusReason) {
        return new FraudEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                "python",
                statusReason == null ? FraudEngineStatus.AVAILABLE : FraudEngineStatus.FALLBACK_USED,
                0.4000d,
                RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM,
                List.of("MODEL_SIGNAL"),
                List.of(),
                List.of(),
                3L,
                modelName,
                modelVersion,
                statusReason,
                now()
        );
    }

    private FraudEngineContribution contribution() {
        return new FraudEngineContribution(
                "TRANSFER_COUNT",
                "HIGH",
                0.2000d,
                FraudEngineContributionDirection.INCREASES_RISK
        );
    }

    private FraudEngineEvidence evidence() {
        return new FraudEngineEvidence(
                FraudEngineEvidenceType.RULE_MATCH,
                "HIGH_VELOCITY",
                "High velocity",
                "Bounded rule scoring signal.",
                "RULES",
                FraudEngineEvidenceStatus.AVAILABLE
        );
    }

    private List<String> unsafeTextTerms() {
        return List.of(
                "raw payload",
                "raw response",
                "feature vector",
                "exception message",
                "stack trace",
                "token",
                "secret",
                "endpoint",
                "customer id",
                "account id",
                "card id",
                "device id",
                "merchant id",
                "final decision",
                "recommended action",
                "payment authorization",
                "ground truth",
                "training label"
        );
    }

    private Instant now() {
        return Instant.parse("2026-06-01T10:15:30Z");
    }
}
