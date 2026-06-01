package com.frauddetection.scoring.orchestration.aggregation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineAggregationWarningSummarizerTest {

    @Test
    void summarizesDuplicateWarningsByCodeInDeterministicOrder() {
        List<FraudEngineAggregationWarningSummary> summaries = FraudEngineAggregationWarningSummarizer.summarize(List.of(
                warning("ml.python.primary", FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED),
                warning("rules.primary", FraudEngineAggregationWarningCode.REASON_CODE_UNSUPPORTED_DROPPED),
                warning("ml.python.primary", FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED)
        ));

        assertThat(summaries)
                .extracting(FraudEngineAggregationWarningSummary::code, FraudEngineAggregationWarningSummary::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FraudEngineAggregationWarningCode.REASON_CODE_UNSUPPORTED_DROPPED, 1),
                        org.assertj.core.groups.Tuple.tuple(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED, 2)
                );
    }

    @Test
    void summaryContainsOnlyBoundedCodeAndCountWithoutRawDetails() {
        List<FraudEngineAggregationWarningSummary> summaries = FraudEngineAggregationWarningSummarizer.summarize(List.of(
                warning("rules.primary", FraudEngineAggregationWarningCode.CONTRIBUTION_UNSAFE_DROPPED)
        ));

        assertThat(Arrays.stream(FraudEngineAggregationWarningSummary.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("code", "count");
        assertThat(summaries.toString()).doesNotContain("rules.primary");
    }

    @Test
    void emptyWarningsProduceEmptySummary() {
        assertThat(FraudEngineAggregationWarningSummarizer.summarize(List.of())).isEmpty();
    }

    @Test
    void unsafeAndUnsupportedDropsAreCounted() {
        List<FraudEngineAggregationWarningSummary> summaries = FraudEngineAggregationWarningSummarizer.summarize(List.of(
                warning("rules.primary", FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED),
                warning("rules.primary", FraudEngineAggregationWarningCode.EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED)
        ));

        assertThat(summaries)
                .extracting(FraudEngineAggregationWarningSummary::code, FraudEngineAggregationWarningSummary::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED, 1),
                        org.assertj.core.groups.Tuple.tuple(FraudEngineAggregationWarningCode.EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED, 1)
                );
    }

    @Test
    void rejectsNegativeCount() {
        assertThatThrownBy(() -> new FraudEngineAggregationWarningSummary(
                FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED,
                -1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private FraudEngineAggregationWarning warning(String engineId, FraudEngineAggregationWarningCode code) {
        return new FraudEngineAggregationWarning(engineId, code);
    }
}
