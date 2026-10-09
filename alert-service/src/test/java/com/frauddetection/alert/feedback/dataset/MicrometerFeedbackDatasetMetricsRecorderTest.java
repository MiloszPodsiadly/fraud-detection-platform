package com.frauddetection.alert.feedback.dataset;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MicrometerFeedbackDatasetMetricsRecorderTest {

    @Test
    void recordsOnlyBoundedBuildResultsAndEvidenceStatuses() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedbackDatasetRecord available = record(
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE
        );
        FeedbackDatasetRecord missing = record(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY);
        FeedbackDatasetBuildResult success = result(FeedbackDatasetBuildFailureReason.NONE, List.of(available, missing));
        FeedbackDatasetBuildResult failure = result(
                FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE,
                List.of()
        );
        var recorder = new MicrometerFeedbackDatasetMetricsRecorder(registry);

        recorder.record(success);
        recorder.record(failure);

        assertThat(registry.get(MicrometerFeedbackDatasetMetricsRecorder.BUILD_METRIC)
                .tag("result", "SUCCESS").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerFeedbackDatasetMetricsRecorder.BUILD_METRIC)
                .tag("result", "ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerFeedbackDatasetMetricsRecorder.EVIDENCE_OUTCOME_METRIC)
                .tag("status", "AVAILABLE").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerFeedbackDatasetMetricsRecorder.EVIDENCE_OUTCOME_METRIC)
                .tag("status", "MISSING_UNEXPECTEDLY").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertBoundedTags(meter.getId()));
    }

    private FeedbackDatasetRecord record(FeedbackDatasetMlPredictionEvidenceStatus status) {
        FeedbackDatasetRecord record = mock(FeedbackDatasetRecord.class);
        when(record.mlPredictionEvidenceStatus()).thenReturn(status);
        return record;
    }

    private FeedbackDatasetBuildResult result(
            FeedbackDatasetBuildFailureReason reason,
            List<FeedbackDatasetRecord> records
    ) {
        FeedbackDatasetBuildResult result = mock(FeedbackDatasetBuildResult.class);
        when(result.failureReason()).thenReturn(reason);
        when(result.failed()).thenReturn(reason != FeedbackDatasetBuildFailureReason.NONE);
        when(result.records()).thenReturn(records);
        return result;
    }

    private void assertBoundedTags(Meter.Id id) {
        Set<String> keys = id.getTags().stream().map(Tag::getKey).collect(java.util.stream.Collectors.toSet());
        assertThat(keys).isSubsetOf("result", "status");
    }
}
