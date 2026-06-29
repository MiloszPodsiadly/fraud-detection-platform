package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibilityPolicy;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackDatasetBuilderTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-06-30T12:00:00Z");

    private final FeedbackDatasetCandidateStore store = mock(FeedbackDatasetCandidateStore.class);
    private final FeedbackDatasetBuilder builder = new FeedbackDatasetBuilder(
            store,
            new FeedbackDatasetMappingPolicy(new FeedbackDatasetEligibilityPolicy()),
            Clock.fixed(BUILT_AT, ZoneOffset.UTC)
    );

    @Test
    void exportsConfirmedFraudAsPositiveFraud() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.records()).singleElement()
                .extracting(FeedbackDatasetRecord::evaluationLabel)
                .isEqualTo(FeedbackEvaluationLabel.POSITIVE_FRAUD);
    }

    @Test
    void exportsConfirmedLegitimateAsNegativeLegitimate() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement()
                .extracting(FeedbackDatasetRecord::evaluationLabel)
                .isEqualTo(FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE);
    }

    @Test
    void excludesUnresolvedLabelsAndCountsThem() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.INCONCLUSIVE, FROM.plusSeconds(1)),
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.NEEDS_MORE_INFO, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.excludedUnresolvedCount()).isEqualTo(2);
    }

    @Test
    void nullLabelIsExcludedForGovernanceReview() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", null, FROM.plusSeconds(1))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.excludedGovernanceReviewCount()).isEqualTo(1);
    }

    @Test
    void missingOptionalDiagnosticFieldsAreAllowed() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        feedback.setFraudScore(null);
        feedback.setRiskLevel(null);
        feedback.setEngineIntelligenceStatus(null);
        feedback.setAnalystRecommendation(null);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().fraudScore()).isNull();
        assertThat(result.records().getFirst().analystRecommendation()).isNull();
    }

    @Test
    void missingRequiredFieldIsSkippedExplicitly() {
        FraudFeedbackRecord missingTransaction = feedback("feedback-1", null, FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        FraudFeedbackRecord missingReason = feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1));
        missingReason.setDecisionReasonCodes(List.of());
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(missingTransaction, missingReason));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedMissingRequiredFieldCount()).isEqualTo(2);
    }

    @Test
    void storeFailureReturnsBoundedFailure() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenThrow(new RuntimeException("db raw token"));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.FEEDBACK_STORE_UNAVAILABLE);
        assertThat(result.records()).isEmpty();
        assertThat(result.toString()).doesNotContain("db raw token");
    }

    @Test
    void invalidRequestReturnsInvalidRequestFailure() {
        FeedbackDatasetBuildResult result = builder.build(new FeedbackDatasetBuildRequest(TO, FROM, 10));

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.INVALID_REQUEST);
        assertThat(result.records()).isEmpty();
    }

    @Test
    void emptyEligibleResultIsSuccessfulEmptyDataset() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of());

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.recordsReturned()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void truncatedResultSetsTruncatedAndCapsRecords() {
        when(store.findBoundedByCreatedAt(FROM, TO, 1)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1)),
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(1));

        assertThat(result.truncated()).isTrue();
        assertThat(result.rawRowsRead()).isEqualTo(2);
        assertThat(result.recordsReturned()).isEqualTo(1);
        assertThat(result.records()).hasSize(1);
    }

    @Test
    void maxRecordsIsCappedAtHardLimit() {
        when(store.findBoundedByCreatedAt(FROM, TO, FeedbackDatasetBuildRequest.HARD_MAX_RECORDS)).thenReturn(List.of());

        builder.build(new FeedbackDatasetBuildRequest(FROM, TO, 5000));

        verify(store).findBoundedByCreatedAt(FROM, TO, FeedbackDatasetBuildRequest.HARD_MAX_RECORDS);
    }

    @Test
    void dateRangeCapIsEnforced() {
        FeedbackDatasetBuildResult result = builder.build(new FeedbackDatasetBuildRequest(
                FROM,
                FROM.plusSeconds(32L * 24L * 60L * 60L),
                10
        ));

        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.INVALID_REQUEST);
    }

    @Test
    void outputOrderFollowsCreatedAtAndFeedbackIdQueryOrder() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1)),
                feedback("feedback-b", "txn-b", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).extracting(FeedbackDatasetRecord::evaluationRecordId)
                .containsExactly(
                        FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-a"),
                        FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-b")
                );
    }

    @Test
    void recordContainsPseudonymousReferencesNotRawIds() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-secret-1", "txn-secret-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM)
        ));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.evaluationRecordId()).doesNotContain("feedback-secret-1");
        assertThat(record.transactionReference()).doesNotContain("txn-secret-1");
    }

    private FeedbackDatasetBuildRequest request(int maxRecords) {
        return new FeedbackDatasetBuildRequest(FROM, TO, maxRecords);
    }

    private FraudFeedbackRecord feedback(
            String feedbackId,
            String transactionId,
            FraudFeedbackLabel label,
            Instant createdAt
    ) {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setFeedbackId(feedbackId);
        record.setTransactionId(transactionId);
        record.setFeedbackLabel(label);
        record.setCreatedAt(createdAt);
        record.setDecisionReasonCodes(List.of("ANALYST_CONFIRMED_FRAUD"));
        record.setFraudScore(0.91);
        record.setRiskLevel(RiskLevel.HIGH);
        return record;
    }
}
