package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineIntelligenceFeedbackDatasetExportServiceTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");
    private static final Instant EXPORTED_AT = Instant.parse("2026-06-30T12:00:00Z");

    @Test
    void rejectsNullDateRange() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetExportRequest(null, TO, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsReversedDateRange() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetExportRequest(TO, FROM, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDateRangeOver31Days() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetExportRequest(
                FROM,
                FROM.plusSeconds(32L * 24L * 60L * 60L),
                10
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxRecordsBelowOne() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetExportRequest(FROM, TO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxRecordsAbove500() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetExportRequest(FROM, TO, 501))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExportRequestRejected() {
        assertThatThrownBy(() -> service(defaultFeedbacks()).export(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request is required");
    }

    @Test
    void failedExportCannotContainRecords() {
        assertThatThrownBy(() -> failedResult(0, 1, false, List.of(record())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failed export must not contain records");
    }

    @Test
    void failedExportCannotHaveRecordsReturned() {
        assertThatThrownBy(() -> failedResult(0, 1, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failed export must not return records");
    }

    @Test
    void failedExportCannotBeTruncated() {
        assertThatThrownBy(() -> failedResult(0, 0, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failed export must not be marked truncated");
    }

    @Test
    void failedExportCannotReportSuccessfulRawRows() {
        assertThatThrownBy(() -> failedResult(1, 0, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failed export must not report successful raw rows");
    }

    @Test
    void emptyExportIsValid() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of());

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.failed()).isFalse();
    }

    @Test
    void exportResultIncludesRawRowsRead() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.rawRowsRead()).isEqualTo(1);
    }

    @Test
    void exportResultIncludesRecordsReturned() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.recordsReturned()).isEqualTo(1);
    }

    @Test
    void exportResultIncludesTruncatedFlag() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 1)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FROM.plusSeconds(20)),
                feedback("feedback-2", "txn-2", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(1));

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exportResultIncludesTimeBasis() {
        assertThat(exportWithOneRecord().timeBasis())
                .isEqualTo(EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT);
    }

    @Test
    void exportResultIncludesDeduplicationPolicy() {
        assertThat(exportWithOneRecord().deduplicationPolicy())
                .isEqualTo(EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC);
    }

    @Test
    void truncatedExportDoesNotLookComplete() {
        EngineIntelligenceFeedbackDatasetExportResult result = new EngineIntelligenceFeedbackDatasetExportResult(
                FROM,
                TO,
                EXPORTED_AT,
                1,
                2,
                0,
                true,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                null,
                List.of()
        );

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exportOrderIsDeterministic() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-a", FROM.plusSeconds(20)),
                feedback("feedback-b", "txn-b", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(10));

        assertThat(result.records()).extracting(EngineIntelligenceFeedbackDatasetRecord::evaluationRecordId)
                .containsExactly(
                        EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId("feedback-a"),
                        EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId("feedback-b")
                );
    }

    @Test
    void dedupUsesNewestSubmittedAt() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-new", "txn-same", FROM.plusSeconds(20)),
                feedback("feedback-old", "txn-same", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(10));

        assertThat(result.records()).singleElement()
                .extracting(EngineIntelligenceFeedbackDatasetRecord::evaluationRecordId)
                .isEqualTo(EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId("feedback-new"));
    }

    @Test
    void feedbackIdTieBreakIsStable() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-same", FROM.plusSeconds(20)),
                feedback("feedback-b", "txn-same", FROM.plusSeconds(20))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(10));

        assertThat(result.records()).singleElement()
                .extracting(EngineIntelligenceFeedbackDatasetRecord::evaluationRecordId)
                .isEqualTo(EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId("feedback-a"));
    }

    @Test
    void filterSortPaginationDedupUseSameTimeBasis() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.timeBasis()).isEqualTo(EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT);
        assertThat(result.deduplicationPolicy().name()).contains("SUBMITTED_AT");
    }

    @Test
    void missingMlScoreIsNullNotZero() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithRulesOnly()).records().getFirst();

        assertThat(record.mlScoreBucket()).isNull();
    }

    @Test
    void missingMlRiskIsNullNotLow() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithRulesOnly()).records().getFirst();

        assertThat(record.mlRiskLevel()).isNull();
    }

    @Test
    void missingRulesScoreIsNullNotZero() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithMlOnly()).records().getFirst();

        assertThat(record.rulesScoreBucket()).isNull();
    }

    @Test
    void missingRulesRiskIsNullNotLow() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithMlOnly()).records().getFirst();

        assertThat(record.rulesRiskLevel()).isNull();
    }

    @Test
    void missingProjectionIsExplicit() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(null).records().getFirst();

        assertThat(record.projectionStatus()).isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING);
    }

    @Test
    void oldTransactionWithoutEngineIntelligenceDoesNotCrash() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(null);

        assertThat(result.failed()).isFalse();
        assertThat(result.records()).hasSize(1);
    }

    @Test
    void missingAlertDecisionMapsToNonTraining() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithDecision(null);

        assertThat(result.records().getFirst().evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
    }

    @Test
    void supportedProjectionContractVersionIsAccepted() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(projectionWithBothEngines());

        assertThat(result.failed()).isFalse();
        assertThat(result.records().getFirst().projectionStatus())
                .isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE);
    }

    @Test
    void unsupportedProjectionContractVersionFailsClosed() {
        assertThat(exportWithProjection(projection(2, "txn-1", projectionEngines())).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void unsupportedProjectionVersionDoesNotCreateDatasetRecord() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(projection(2, "txn-1", projectionEngines()));

        assertThat(result.failed()).isTrue();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void alertTransactionMismatchFailsClosed() {
        assertThat(exportWithAlert(alert("txn-other", AnalystDecision.CONFIRMED_FRAUD)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_ALERT_DATA);
    }

    @Test
    void projectionTransactionMismatchFailsClosed() {
        assertThat(exportWithProjection(projection(1, "txn-other", projectionEngines())).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void alertMismatchDoesNotExportPositiveOrNegativeLabel() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithAlert(
                alert("txn-other", AnalystDecision.CONFIRMED_FRAUD)
        );

        assertThat(result.records()).isEmpty();
    }

    @Test
    void projectionMismatchDoesNotExportEngineSignals() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(
                projection(1, "txn-other", projectionEngines())
        );

        assertThat(result.records()).isEmpty();
    }

    @Test
    void feedbackAlertProjectionTransactionIdsMustMatch() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.failed()).isFalse();
        assertThat(result.records()).hasSize(1);
    }

    @Test
    void missingDecisionAlertUsesFeedbackTransactionId() {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        EngineIntelligenceFeedbackDatasetExportResult result =
                service(defaultFeedbacks(), alerts, defaultProjections()).export(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.records().getFirst().evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
    }

    @Test
    void unsafeProjectionReasonCodeFailsAsCorruptedProjection() {
        EngineIntelligenceProjection projection = projection(
                1,
                "txn-1",
                List.of(engine(FraudEngineType.ML_MODEL, "ml.python.primary", List.of("deviceId:abc")))
        );

        assertThat(exportWithProjection(projection).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void tooManyProjectionReasonCodesFailsAsCorruptedProjection() {
        EngineIntelligenceProjection projection = projection(
                1,
                "txn-1",
                List.of(engine(FraudEngineType.ML_MODEL, "ml.python.primary", List.of(
                        "CODE_A", "CODE_B", "CODE_C", "CODE_D", "CODE_E", "CODE_F"
                )))
        );

        assertThat(exportWithProjection(projection).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void unsafeProjectionDiagnosticSignalFailsAsCorruptedProjection() {
        EngineIntelligenceProjection projection = projection(
                1,
                "txn-1",
                projectionEngines(),
                "customer_123_flagged"
        );

        assertThat(exportWithProjection(projection).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void unsafeFeedbackSelectedReasonCodeFailsAsCorruptedFeedback() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FROM.plusSeconds(1), List.of("deviceId:abc"))
        ));

        assertThat(service(feedbacks).export(request(10)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK);
    }

    @Test
    void corruptedProjectionFailsClosed() {
        EngineIntelligenceProjection corrupted = new EngineIntelligenceProjection(
                null,
                1,
                FROM,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                FROM,
                FROM
        );

        assertThat(exportWithProjection(corrupted).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void corruptedFeedbackFailsClosed() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                new EngineIntelligenceFeedbackDocument(
                        null,
                        "txn-1",
                        true,
                        EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                        EngineIntelligenceFeedbackUsefulness.HELPFUL,
                        EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                        List.of(),
                        "analyst-1",
                        FROM,
                        "corr",
                        "hash",
                        "payload",
                        FROM
                )
        ));

        assertThat(service(feedbacks).export(request(10)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK);
    }

    @Test
    void corruptedAlertDataFailsClosed() {
        AlertDocument corrupted = new AlertDocument();
        assertThat(exportWithAlert(corrupted).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_ALERT_DATA);
    }

    @Test
    void feedbackStoreFailureReturnsBoundedFailureReason() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenThrow(new RuntimeException("db down raw"));

        assertThat(service(feedbacks).export(request(10)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.FEEDBACK_STORE_UNAVAILABLE);
    }

    @Test
    void projectionStoreFailureReturnsBoundedFailureReason() {
        EngineIntelligenceProjectionRepository projections = mock(EngineIntelligenceProjectionRepository.class);
        when(projections.findById("txn-1")).thenThrow(new RuntimeException("projection raw"));

        assertThat(service(defaultFeedbacks(), defaultAlerts(), projections).export(request(10)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.PROJECTION_STORE_UNAVAILABLE);
    }

    @Test
    void alertStoreFailureReturnsBoundedFailureReason() {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findByTransactionId("txn-1")).thenThrow(new RuntimeException("alert raw"));

        assertThat(service(defaultFeedbacks(), alerts, defaultProjections()).export(request(10)).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.ALERT_STORE_UNAVAILABLE);
    }

    @Test
    void rawExceptionMessageDoesNotLeaveExport() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjectionStoreFailure();

        assertThat(result.failureReason()).isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.PROJECTION_STORE_UNAVAILABLE);
        assertThat(result.toString()).doesNotContain("projection raw");
    }

    @Test
    void truncatedTrueEvenWhenDeduplicationReturnsFewerThanMaxRecords() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 2)).thenReturn(List.of(
                feedback("feedback-a", "txn-same", FROM.plusSeconds(30)),
                feedback("feedback-b", "txn-same", FROM.plusSeconds(20)),
                feedback("feedback-c", "txn-same", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(2));

        assertThat(result.truncated()).isTrue();
        assertThat(result.recordsReturned()).isEqualTo(1);
    }

    @Test
    void recordsReturnedCanBeLessThanMaxRecordsWhenDedupDropsRows() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-same", FROM.plusSeconds(30)),
                feedback("feedback-b", "txn-same", FROM.plusSeconds(20))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(10));

        assertThat(result.recordsReturned()).isLessThan(result.maxRecords());
        assertThat(result.rawRowsRead()).isEqualTo(2);
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithOneRecord() {
        return service(defaultFeedbacks()).export(request(10));
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithDecision(AnalystDecision decision) {
        AlertDocument alert = alert("txn-1", decision);
        return exportWithAlert(alert);
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithAlert(AlertDocument alert) {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findByTransactionId("txn-1")).thenReturn(Optional.of(alert));
        return service(defaultFeedbacks(), alerts, defaultProjections()).export(request(10));
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithProjection(EngineIntelligenceProjection projection) {
        EngineIntelligenceProjectionRepository projections = mock(EngineIntelligenceProjectionRepository.class);
        when(projections.findById("txn-1")).thenReturn(Optional.ofNullable(projection));
        return service(defaultFeedbacks(), defaultAlerts(), projections).export(request(10));
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithProjectionStoreFailure() {
        EngineIntelligenceProjectionRepository projections = mock(EngineIntelligenceProjectionRepository.class);
        when(projections.findById("txn-1")).thenThrow(new RuntimeException("projection raw"));
        return service(defaultFeedbacks(), defaultAlerts(), projections).export(request(10));
    }

    private EngineIntelligenceFeedbackDatasetExportResult failedResult(
            int rawRowsRead,
            int recordsReturned,
            boolean truncated,
            List<EngineIntelligenceFeedbackDatasetRecord> records
    ) {
        return new EngineIntelligenceFeedbackDatasetExportResult(
                FROM,
                TO,
                EXPORTED_AT,
                10,
                rawRowsRead,
                recordsReturned,
                truncated,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK,
                records
        );
    }

    private EngineIntelligenceFeedbackDatasetRecord record() {
        return new EngineIntelligenceFeedbackDatasetRecord(
                "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                FROM,
                EngineIntelligenceFeedbackDatasetLabel.ANALYST_CONFIRMED_FRAUD,
                EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("ML_MODEL_SIGNAL"),
                List.of("ML_MODEL_SIGNAL")
        );
    }

    private EngineIntelligenceFeedbackDatasetExportService service(EngineIntelligenceFeedbackDatasetQueryRepository feedbacks) {
        return service(feedbacks, defaultAlerts(), defaultProjections());
    }

    private EngineIntelligenceFeedbackDatasetExportService service(
            EngineIntelligenceFeedbackDatasetQueryRepository feedbacks,
            AlertRepository alerts,
            EngineIntelligenceProjectionRepository projections
    ) {
        return new EngineIntelligenceFeedbackDatasetExportService(
                feedbacks,
                alerts,
                projections,
                new EngineIntelligenceFeedbackDatasetRecordMapper(),
                Clock.fixed(EXPORTED_AT, ZoneOffset.UTC)
        );
    }

    private EngineIntelligenceFeedbackDatasetQueryRepository defaultFeedbacks() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(feedback("feedback-1", "txn-1", FROM.plusSeconds(1))));
        return feedbacks;
    }

    private AlertRepository defaultAlerts() {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findByTransactionId("txn-1")).thenReturn(Optional.of(alert("txn-1", AnalystDecision.CONFIRMED_FRAUD)));
        when(alerts.findByTransactionId("txn-a")).thenReturn(Optional.of(alert("txn-a", AnalystDecision.CONFIRMED_FRAUD)));
        when(alerts.findByTransactionId("txn-b")).thenReturn(Optional.of(alert("txn-b", AnalystDecision.MARKED_LEGITIMATE)));
        when(alerts.findByTransactionId("txn-same")).thenReturn(Optional.of(alert("txn-same", AnalystDecision.CONFIRMED_FRAUD)));
        return alerts;
    }

    private EngineIntelligenceProjectionRepository defaultProjections() {
        EngineIntelligenceProjectionRepository projections = mock(EngineIntelligenceProjectionRepository.class);
        when(projections.findById("txn-1")).thenReturn(Optional.of(projectionWithBothEngines()));
        when(projections.findById("txn-a")).thenReturn(Optional.empty());
        when(projections.findById("txn-b")).thenReturn(Optional.empty());
        when(projections.findById("txn-same")).thenReturn(Optional.empty());
        return projections;
    }

    private EngineIntelligenceFeedbackDatasetExportRequest request(int maxRecords) {
        return new EngineIntelligenceFeedbackDatasetExportRequest(FROM, TO, maxRecords);
    }

    private EngineIntelligenceFeedbackDocument feedback(String feedbackId, String transactionId, Instant submittedAt) {
        return feedback(feedbackId, transactionId, submittedAt, List.of("ML_MODEL_SIGNAL"));
    }

    private EngineIntelligenceFeedbackDocument feedback(
            String feedbackId,
            String transactionId,
            Instant submittedAt,
            List<String> selectedReasonCodes
    ) {
        return new EngineIntelligenceFeedbackDocument(
                feedbackId,
                transactionId,
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                selectedReasonCodes,
                "analyst-1",
                submittedAt,
                "corr",
                "key-hash",
                "payload-hash",
                submittedAt
        );
    }

    private AlertDocument alert(String transactionId, AnalystDecision decision) {
        AlertDocument alert = new AlertDocument();
        alert.setTransactionId(transactionId);
        alert.setAnalystDecision(decision);
        return alert;
    }

    private EngineIntelligenceProjection projectionWithBothEngines() {
        return projection(1, "txn-1", projectionEngines());
    }

    private EngineIntelligenceProjection projectionWithMlOnly() {
        return projection(1, "txn-1", List.of(engine(FraudEngineType.ML_MODEL, "ml.python.primary")));
    }

    private EngineIntelligenceProjection projectionWithRulesOnly() {
        return projection(1, "txn-1", List.of(engine(FraudEngineType.RULES, "rules.primary")));
    }

    private List<EngineIntelligenceEngineProjection> projectionEngines() {
        return List.of(
                engine(FraudEngineType.ML_MODEL, "ml.python.primary"),
                engine(FraudEngineType.RULES, "rules.primary")
        );
    }

    private EngineIntelligenceProjection projection(
            int contractVersion,
            String transactionId,
            List<EngineIntelligenceEngineProjection> engines
    ) {
        return projection(contractVersion, transactionId, engines, "ML_MODEL_SIGNAL");
    }

    private EngineIntelligenceProjection projection(
            int contractVersion,
            String transactionId,
            List<EngineIntelligenceEngineProjection> engines,
            String diagnosticReasonCode
    ) {
        return new EngineIntelligenceProjection(
                transactionId,
                contractVersion,
                FROM,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.NONE,
                engines,
                List.of(new EngineIntelligenceDiagnosticSignalProjection(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        diagnosticReasonCode
                )),
                List.of(),
                FROM,
                FROM
        );
    }

    private EngineIntelligenceEngineProjection engine(FraudEngineType engineType, String engineId) {
        return engine(engineType, engineId, List.of("ML_MODEL_SIGNAL"));
    }

    private EngineIntelligenceEngineProjection engine(
            FraudEngineType engineType,
            String engineId,
            List<String> reasonCodes
    ) {
        return new EngineIntelligenceEngineProjection(
                engineId,
                engineType,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                reasonCodes
        );
    }
}
