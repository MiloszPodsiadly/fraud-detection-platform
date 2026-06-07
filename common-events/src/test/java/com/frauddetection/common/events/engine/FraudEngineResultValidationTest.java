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
            FraudEngineResult result = result(status, null, null);

            assertThat(result.score()).isNull();
            assertThat(result.riskLevel()).isNull();
            assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        }
    }

    @Test
    void operationalStatusesRejectScoreRiskAndKnownConfidence() {
        assertThatThrownBy(() -> result(FraudEngineStatus.UNAVAILABLE, 0.1000d, null))
                .hasMessageContaining("must not declare score");
        assertThatThrownBy(() -> result(FraudEngineStatus.TIMEOUT, null, RiskLevel.LOW))
                .hasMessageContaining("must not declare riskLevel");
        assertThatThrownBy(() -> new FraudEngineResult("ml.python.primary", FraudEngineType.ML_MODEL, "python",
                FraudEngineStatus.SKIPPED, null, null, FraudEngineConfidence.LOW, List.of(), List.of(), List.of(),
                3L, null, null, null, now()))
                .hasMessageContaining("UNKNOWN confidence");
    }

    @Test
    void fallbackAndDegradedScoreRiskArePaired() {
        assertThat(fallback(0.5000d, RiskLevel.MEDIUM).score()).isEqualTo(0.5d);
        assertThatThrownBy(() -> fallback(0.5000d, null)).hasMessageContaining("provided together");
        assertThatThrownBy(() -> fallback(null, RiskLevel.MEDIUM)).hasMessageContaining("provided together");
        assertThatThrownBy(() -> fallbackWithoutStatusReason()).hasMessageContaining("statusReason");

        assertThat(degraded(0.5000d, RiskLevel.MEDIUM).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThatThrownBy(() -> degraded(0.5000d, null)).hasMessageContaining("provided together");
        assertThatThrownBy(() -> degraded(null, RiskLevel.MEDIUM)).hasMessageContaining("provided together");
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
    void contributionValidationIsBoundedAndCompatible() {
        FraudEngineContribution contribution = contribution();

        assertThat(contribution.feature()).isEqualTo("TRANSFER_COUNT");
        assertThat(contribution.featureCode()).isEqualTo("TRANSFER_COUNT");
        assertThat(contribution.valueBucket()).isEqualTo("HIGH");
        assertThatThrownBy(() -> new FraudEngineContribution(null, null, null,
                FraudEngineContributionDirection.NEUTRAL)).hasMessageContaining("feature");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, null, null))
                .hasMessageContaining("direction");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, -1.0001d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 1.0001d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight");
        assertThatThrownBy(() -> new FraudEngineContribution("TRANSFER_COUNT", null, 0.12345d,
                FraudEngineContributionDirection.INCREASES_RISK)).hasMessageContaining("weight scale");
    }

    @Test
    void evidenceValidationIsBoundedAndCompatible() {
        FraudEngineEvidence evidence = evidence();

        assertThat(evidence.reasonCode()).isEqualTo("HIGH_VELOCITY");
        assertThat(evidence.evidenceCode()).isEqualTo("HIGH_VELOCITY");
        assertThat(evidence.descriptionCode()).isEqualTo("High velocity");
        assertThatThrownBy(() -> new FraudEngineEvidence(null, "HIGH_VELOCITY", "High velocity",
                null, "RULES", FraudEngineEvidenceStatus.AVAILABLE)).hasMessageContaining("evidenceType");
        assertThat(new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, null,
                "High velocity", null, "RULES", FraudEngineEvidenceStatus.AVAILABLE).reasonCode())
                .isNull();
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                "High velocity", null, null, FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "HIGH_VELOCITY",
                "High velocity", null, "RULES", null)).hasMessageContaining("status");
    }

    @Test
    void forbiddenRawPiiDecisioningAndTrainingTermsAreRejected() {
        for (String unsafe : List.of("RAW_PAYLOAD", "FEATURE_VECTOR", "STACK_TRACE", "TOKEN", "SECRET",
                "ENDPOINT", "CUSTOMER_ID", "ACCOUNT_ID", "CARD_ID", "DEVICE_ID", "MERCHANT_ID",
                "GROUND_TRUTH", "TRAINING_LABEL", "FINAL_DECISION", "RECOMMENDED_ACTION",
                "PAYMENT_AUTHORIZATION", "RULE_UPDATE")) {
            FraudEngineContribution contribution = new FraudEngineContribution(unsafe, null, null,
                    FraudEngineContributionDirection.NEUTRAL);
            assertThatThrownBy(() -> resultWithLists(List.of(), List.of(contribution), List.of()))
                    .as(unsafe)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    void noUnboundedContractTypesAreIntroduced() {
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
                List.of(),
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
                "RULE_ENGINE_FALLBACK",
                now()
        );
    }

    private FraudEngineResult fallbackWithoutStatusReason() {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.FALLBACK_USED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of("FALLBACK_PATH_USED"),
                List.of(),
                List.of(),
                3L,
                null,
                null,
                null,
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
                List.of(),
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
                List.of(),
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

    private Instant now() {
        return Instant.parse("2026-06-01T10:15:30Z");
    }
}
