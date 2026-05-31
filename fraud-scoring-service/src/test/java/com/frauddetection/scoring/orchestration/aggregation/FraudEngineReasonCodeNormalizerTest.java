package com.frauddetection.scoring.orchestration.aggregation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineReasonCodeNormalizerTest {
    private final FraudEngineReasonCodeNormalizer normalizer = new FraudEngineReasonCodeNormalizer();
    private final FraudEngineAggregationPolicy policy = FraudEngineAggregationPolicy.defaultInternalPolicy();

    @Test
    void deduplicatesKnownCodesAndDropsNullBlankAndUnsupported() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<String> result = normalizer.normalize(
                "rules.primary",
                List.of("MODEL_HIGH_RISK", "MODEL_HIGH_RISK", " ", "raw-token-endpoint"),
                policy,
                warnings
        );

        assertThat(result).containsExactly("MODEL_HIGH_RISK");
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.REASON_CODE_BLANK_DROPPED,
                        FraudEngineAggregationWarningCode.REASON_CODE_UNSUPPORTED_DROPPED
                );
        assertThat(result.toString()).doesNotContain("raw-token-endpoint");
    }

    @Test
    void dropsNullCodeWithBoundedWarning() {
        List<String> source = new ArrayList<>();
        source.add(null);
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        assertThat(normalizer.normalize("rules.primary", source, policy, warnings)).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .containsExactly(FraudEngineAggregationWarningCode.REASON_CODE_NULL_DROPPED);
    }

    @Test
    void truncatesDeterministicallyAtPolicyLimit() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(2, 2, 5, 5, 5, 20, 128, 120, 256);
        List<String> source = List.of("MODEL_HIGH_RISK", "LOW_MODEL_RISK", "ML_MODEL_UNAVAILABLE");
        List<FraudEngineAggregationWarning> firstWarnings = new ArrayList<>();
        List<FraudEngineAggregationWarning> secondWarnings = new ArrayList<>();

        assertThat(normalizer.normalize("ml.python.primary", source, strict, firstWarnings))
                .isEqualTo(normalizer.normalize("ml.python.primary", source, strict, secondWarnings))
                .containsExactly("MODEL_HIGH_RISK", "LOW_MODEL_RISK");
        assertThat(firstWarnings).extracting(FraudEngineAggregationWarning::code)
                .contains(FraudEngineAggregationWarningCode.REASON_CODE_LIMIT_APPLIED);
    }
}
