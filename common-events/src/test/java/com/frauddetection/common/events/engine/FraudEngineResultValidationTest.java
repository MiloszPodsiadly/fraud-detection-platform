package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineResultValidationTest {

    @Test
    void rejectsInvalidScoresAndNegativeLatency() {
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, -0.01d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(), 4L, null, null, null))
                .hasMessageContaining("score");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 1.01d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(), 4L, null, null, null))
                .hasMessageContaining("score");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.50d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(), -1L, null, null, null))
                .hasMessageContaining("latencyMs");
    }

    @Test
    void requiresEngineIdGenerationTimeAndCanonicalEngineLanguage() {
        assertThatThrownBy(() -> new FraudEngineResult(
                " ", FraudEngineType.RULES, "java", FraudEngineStatus.AVAILABLE, 0.10d, RiskLevel.LOW,
                FraudEngineConfidence.LOW, List.of(), List.of(), List.of(), 1L, null, null, null, now()))
                .hasMessageContaining("engineId");
        assertThatThrownBy(() -> new FraudEngineResult(
                "rules-v1", FraudEngineType.RULES, "java", FraudEngineStatus.AVAILABLE, 0.10d, RiskLevel.LOW,
                FraudEngineConfidence.LOW, List.of(), List.of(), List.of(), 1L, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generatedAt");
        for (String invalid : List.of("Java", "Python3", "py", " ")) {
            assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.30d, RiskLevel.LOW,
                    FraudEngineConfidence.LOW, List.of(), List.of(), List.of(), 1L, null, null, null, invalid))
                    .as(invalid)
                    .hasMessageContaining("engineLanguage");
        }
        for (String valid : List.of("java", "python", "go", "kotlin", "scala", "javascript", "other")) {
            assertThat(result(FraudEngineStatus.AVAILABLE, 0.30d, RiskLevel.LOW,
                    FraudEngineConfidence.LOW, List.of(), List.of(), List.of(), 1L, null, null, null, valid)
                    .engineLanguage()).isEqualTo(valid);
        }
    }

    @Test
    void enforcesBoundedCollectionSizesAtBoundary() {
        FraudEngineResult atBoundary = resultWithLists(
                Collections.nCopies(FraudEngineResult.MAX_REASON_CODES, "deviceNovelty"),
                Collections.nCopies(FraudEngineResult.MAX_CONTRIBUTIONS, contribution()),
                Collections.nCopies(FraudEngineResult.MAX_EVIDENCE, evidence()));

        assertThat(atBoundary.reasonCodes()).hasSize(32);
        assertThat(atBoundary.contributions()).hasSize(32);
        assertThat(atBoundary.evidence()).hasSize(16);

        assertThatThrownBy(() -> resultWithLists(Collections.nCopies(33, "deviceNovelty"), List.of(), List.of()))
                .hasMessageContaining("reasonCodes")
                .hasMessageContaining("32");
        assertThatThrownBy(() -> resultWithLists(List.of(), Collections.nCopies(33, contribution()), List.of()))
                .hasMessageContaining("contributions")
                .hasMessageContaining("32");
        assertThatThrownBy(() -> resultWithLists(List.of(), List.of(), Collections.nCopies(17, evidence())))
                .hasMessageContaining("evidence")
                .hasMessageContaining("16");
    }

    @Test
    void enforcesAvailableSemantics() {
        assertThat(validAvailable().status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.AVAILABLE, null, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, null)).hasMessageContaining("score");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.AVAILABLE, 0.40d, null,
                FraudEngineConfidence.MEDIUM, null)).hasMessageContaining("riskLevel");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.AVAILABLE, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.UNKNOWN, null)).hasMessageContaining("confidence");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.AVAILABLE, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, "MODEL_FALLBACK")).hasMessageContaining("fallbackReason");
    }

    @Test
    void enforcesUnavailableTimeoutAndSkippedSemantics() {
        for (FraudEngineStatus status : List.of(
                FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.TIMEOUT, FraudEngineStatus.SKIPPED)) {
            assertThat(statusResult(status, null, null, FraudEngineConfidence.UNKNOWN, "ENGINE_NOT_AVAILABLE").status())
                    .isEqualTo(status);
            assertThatThrownBy(() -> statusResult(status, 0.40d, null,
                    FraudEngineConfidence.UNKNOWN, "ENGINE_NOT_AVAILABLE")).hasMessageContaining("score");
            assertThatThrownBy(() -> statusResult(status, null, RiskLevel.LOW,
                    FraudEngineConfidence.UNKNOWN, "ENGINE_NOT_AVAILABLE")).hasMessageContaining("riskLevel");
            assertThatThrownBy(() -> statusResult(status, null, null,
                    FraudEngineConfidence.UNKNOWN, null)).hasMessageContaining("fallbackReason");
        }
    }

    @Test
    void enforcesDegradedAndFallbackSemantics() {
        assertThat(statusResult(FraudEngineStatus.DEGRADED, null, null,
                FraudEngineConfidence.UNKNOWN, "PARTIAL_CONTEXT").status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(statusResult(FraudEngineStatus.DEGRADED, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.LOW, "PARTIAL_CONTEXT").score()).isEqualTo(0.40d);
        assertThat(statusResult(FraudEngineStatus.FALLBACK_USED, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, "MODEL_FALLBACK").status()).isEqualTo(FraudEngineStatus.FALLBACK_USED);

        assertThatThrownBy(() -> statusResult(FraudEngineStatus.DEGRADED, 0.40d, null,
                FraudEngineConfidence.LOW, "PARTIAL_CONTEXT")).hasMessageContaining("together");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.DEGRADED, null, null,
                FraudEngineConfidence.HIGH, "PARTIAL_CONTEXT")).hasMessageContaining("HIGH");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.FALLBACK_USED, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, null)).hasMessageContaining("fallbackReason");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.FALLBACK_USED, 0.40d, RiskLevel.MEDIUM,
                FraudEngineConfidence.HIGH, "MODEL_FALLBACK")).hasMessageContaining("HIGH");
    }

    @Test
    void reasonCodesAreMachineReadableAndFallbackCodeRemainsStrict() {
        for (String valid : List.of("rapidTransferBurst", "DEVICE_NOVELTY", "model.reason.v1", "device-risk:v1")) {
            assertThat(resultWithLists(List.of(valid), List.of(), List.of()).reasonCodes()).containsExactly(valid);
        }
        for (String invalid : List.of(
                "customer called from +48...",
                "connection refused at internal host",
                "RAPID TRANSFER BURST",
                "bad\ncode",
                "bad\tcode",
                "",
                " ")) {
            assertThatThrownBy(() -> resultWithLists(List.of(invalid), List.of(), List.of()))
                    .as(invalid)
                    .hasMessageContaining("reasonCode");
        }
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.UNAVAILABLE, null, null,
                FraudEngineConfidence.UNKNOWN, "connection refused at internal host"))
                .hasMessageContaining("fallbackReason");
        assertThatThrownBy(() -> statusResult(FraudEngineStatus.UNAVAILABLE, null, null,
                FraudEngineConfidence.UNKNOWN, " "))
                .hasMessageContaining("fallbackReason");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.RULE_MATCH, "bad code",
                "Title", null, "RULES", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("reasonCode");
    }

    @Test
    void optionalStringsAreNullOrNonBlankAndSummariesRejectUnsafeContent() {
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.50d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(), 1L, " ", null, null))
                .hasMessageContaining("modelName");
        assertThatThrownBy(() -> result(FraudEngineStatus.AVAILABLE, 0.50d, RiskLevel.MEDIUM,
                FraudEngineConfidence.MEDIUM, List.of(), List.of(), List.of(), 1L, null, "\t", null))
                .hasMessageContaining("modelVersion");
        assertThatThrownBy(() -> new FraudEngineContribution("feature", "", null, null))
                .hasMessageContaining("value");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, " ",
                "Model context", null, "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null,
                "Model context", " ", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("description");
        assertThatThrownBy(() -> new FraudEngineContribution("feature", "raw payload attached", null, null))
                .hasMessageContaining("safe bounded summary");
        assertThatThrownBy(() -> new FraudEngineContribution("feature", "safe\tvalue", null, null))
                .hasMessageContaining("control");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null,
                "Model context", "Exception from model host", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("safe bounded summary");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null,
                "Model context", "stack trace available", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("safe bounded summary");
        assertThatThrownBy(() -> new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null,
                "Model\ncontext", null, "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE))
                .hasMessageContaining("control");
        assertThat(new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null,
                "Model context", "Bounded explanation summary.", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE)
                .description()).isEqualTo("Bounded explanation summary.");
    }

    @Test
    void resultDoesNotExposeRawSensitivePayloadFields() {
        assertThat(Arrays.stream(FraudEngineResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("rawPayload", "rawFeatures", "customerPayload", "stackTrace", "exception", "token", "secret");
    }

    private FraudEngineResult validAvailable() {
        return statusResult(FraudEngineStatus.AVAILABLE, 0.40d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM, null);
    }

    private FraudEngineResult resultWithLists(
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence
    ) {
        return result(FraudEngineStatus.AVAILABLE, 0.40d, RiskLevel.MEDIUM, FraudEngineConfidence.MEDIUM,
                reasonCodes, contributions, evidence, 3L, null, null, null);
    }

    private FraudEngineResult statusResult(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            String fallbackReason
    ) {
        return result(status, score, riskLevel, confidence, List.of(), List.of(), List.of(), 3L,
                null, null, fallbackReason);
    }

    private FraudEngineResult result(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence,
            Long latencyMs,
            String modelName,
            String modelVersion,
            String fallbackReason
    ) {
        return result(status, score, riskLevel, confidence, reasonCodes, contributions, evidence, latencyMs,
                modelName, modelVersion, fallbackReason, "python");
    }

    private FraudEngineResult result(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence,
            Long latencyMs,
            String modelName,
            String modelVersion,
            String fallbackReason,
            String engineLanguage
    ) {
        return new FraudEngineResult(
                "engine-v1", FraudEngineType.ML_MODEL, engineLanguage, status, score, riskLevel, confidence,
                reasonCodes, contributions, evidence, latencyMs, modelName, modelVersion, fallbackReason, now());
    }

    private FraudEngineContribution contribution() {
        return new FraudEngineContribution("deviceNovelty", "new-device", 0.20d,
                FraudEngineContributionDirection.INCREASES_RISK);
    }

    private FraudEngineEvidence evidence() {
        return new FraudEngineEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, "deviceNovelty",
                "Novel device context", "Bounded explanation summary.", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE);
    }

    private Instant now() {
        return Instant.parse("2026-05-25T09:00:00Z");
    }
}
