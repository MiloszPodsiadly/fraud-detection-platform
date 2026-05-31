package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineEvidenceSanitizerTest {
    private final FraudEngineEvidenceSanitizer sanitizer = new FraudEngineEvidenceSanitizer();

    @Test
    void truncatesCountAndTextDeterministically() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(2, 10, 1, 5, 5, 20, 128, 10, 12);
        List<FraudEngineEvidence> source = List.of(
                evidence("MODEL_HIGH_RISK", "A safe bounded title", "A safe bounded description"),
                evidence("LOW_MODEL_RISK", "Second title", "Second description")
        );
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize("ml.python.primary", source, strict, warnings);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).hasSize(10);
        assertThat(result.getFirst().description()).hasSize(12);
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.EVIDENCE_TEXT_TRUNCATED,
                        FraudEngineAggregationWarningCode.EVIDENCE_LIMIT_APPLIED
                );
    }

    @Test
    void dropsUnsafeEvidenceWithoutLeakingRawText() {
        List<String> forbidden = List.of(
                "transactionId.raw",
                "customerId.raw",
                "accountId.raw",
                "cardId.raw",
                "merchantId.raw",
                "endpoint.raw",
                "token.raw",
                "secret.raw",
                "stacktrace.raw",
                "rawFeatureVector.raw"
        );
        List<FraudEngineEvidence> source = forbidden.stream()
                .map(reason -> evidence(reason, "Safe title", "Safe description"))
                .toList();
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "rules.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
        assertThat(result.toString()).doesNotContain("transactionId", "accountId", "endpoint", "token", "stacktrace");
    }

    @Test
    void dropsEvidenceWithUnsupportedReasonCode() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                List.of(evidence("UNSUPPORTED_SAFE_RAW", "Safe title", "Safe description")),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .containsExactly(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
    }

    @Test
    void keepsEvidenceWithoutOptionalReasonCode() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                List.of(evidence(null, "Safe title", "Safe description")),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).singleElement().extracting(BoundedFraudEngineEvidenceSummary::reasonCode).isNull();
        assertThat(warnings).isEmpty();
    }

    private FraudEngineEvidence evidence(String reasonCode, String title, String description) {
        return new FraudEngineEvidence(
                FraudEngineEvidenceType.MODEL_EXPLANATION,
                reasonCode,
                title,
                description,
                "ML_MODEL",
                FraudEngineEvidenceStatus.AVAILABLE
        );
    }
}
