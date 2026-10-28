package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
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
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void metadataReportsRawRowsReadAndRecordsReturned() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.rawRowsRead()).isEqualTo(1);
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
    void truncatedTrueWhenMoreRawRowsExistThanMaxRecords() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 1)).thenReturn(List.of(
                feedback("feedback-1", "txn-a", FROM.plusSeconds(20)),
                feedback("feedback-2", "txn-b", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(1));

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void truncatedFalseWhenRawRowsEqualMaxRecords() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 2)).thenReturn(List.of(
                feedback("feedback-1", "txn-a", FROM.plusSeconds(20)),
                feedback("feedback-2", "txn-b", FROM.plusSeconds(10))
        ));

        EngineIntelligenceFeedbackDatasetExportResult result = service(feedbacks).export(request(2));

        assertThat(result.truncated()).isFalse();
        assertThat(result.rawRowsRead()).isEqualTo(2);
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
    void newestSubmittedAtFeedbackWinsForSameTransactionReference() {
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
    void feedbackIdAscIsTieBreakerForSameSubmittedAt() {
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
    void deduplicationPolicyMatchesRepositorySort() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.deduplicationPolicy())
                .isEqualTo(EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC);
    }

    @Test
    void filterSortPaginationDedupUseSameTimeBasis() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithOneRecord();

        assertThat(result.timeBasis()).isEqualTo(EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT);
        assertThat(result.deduplicationPolicy().name()).contains("SUBMITTED_AT");
    }

    @Test
    void unavailableMlScoreRemainsUnavailableNotZero() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithRulesOnly()).records().getFirst();

        assertThat(record.mlScoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void missingMlRiskIsNullNotLow() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithRulesOnly()).records().getFirst();

        assertThat(record.mlRiskLevel()).isNull();
    }

    @Test
    void unavailableRulesScoreRemainsUnavailableNotZero() {
        EngineIntelligenceFeedbackDatasetRecord record = exportWithProjection(projectionWithMlOnly()).records().getFirst();

        assertThat(record.rulesScoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
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
    void version2ProjectionWithDeviceStatusIsAcceptedWithoutRiskInference() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(v2DeviceProjection());

        assertThat(result.failed()).as("failureReason=%s", result.failureReason()).isFalse();
        assertThat(result.records().getFirst().engineIntelligenceContractVersion()).isEqualTo(2);
        assertThat(result.records().getFirst().deviceEngineStatus()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.records().getFirst().deviceScoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
        assertThat(result.records().getFirst().deviceRiskLevel()).isNull();
    }

    @Test
    void acceptsEveryCanonicalDeviceReasonCodeForDeviceEngineAndSignal() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);

        for (String reasonCode : EngineIntelligenceReasonCodePolicy.deviceReasonCodes()) {
            EngineIntelligenceFeedbackDatasetRecord record = mapper.map(
                    feedback, alert, v2DeviceProjection(reasonCode));

            assertThat(record.reasonCodes()).as("device engine reason code %s", reasonCode).contains(reasonCode);
            assertThat(record.diagnosticSignals()).as("device signal reason code %s", reasonCode).contains(reasonCode);
        }
    }

    @Test
    void rejectsEveryDeviceOwnedReasonCodeForNonDeviceEnginesAndSignals() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);

        for (String reasonCode : EngineIntelligenceReasonCodePolicy.deviceReasonCodes()) {
            for (String engineId : FraudEngineIdentityContract.engineOrder()) {
                if (FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engineId)) {
                    continue;
                }
                FraudEngineType engineType = FraudEngineIdentityContract.expectedTypeFor(engineId).orElseThrow();
                String ownedReasonCode = validReasonCodeFor(engineType);

                assertCorruptedProjection(
                        mapper,
                        feedback,
                        alert,
                        projection(2, "txn-1", engineProfileWithReasonCodes(engineId, List.of(reasonCode)))
                );

                EngineIntelligenceDiagnosticSignalProjection invalidSignal = new EngineIntelligenceDiagnosticSignalProjection(
                        engineId,
                        engineType,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        reasonCode
                );
                EngineIntelligenceProjection invalidSignalProjection = projectionWithSignals(
                        engineProfileWithReasonCodes(engineId, List.of(ownedReasonCode)),
                        List.of(invalidSignal)
                );
                assertCorruptedProjection(mapper, feedback, alert, invalidSignalProjection);
            }
        }
    }

    @Test
    void retainsRulesMlVelocityAndSharedOrchestratorReasonCodes() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);
        List<EngineIntelligenceEngineProjection> engines = List.of(
                engine(FraudEngineType.RULES, FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        List.of("HIGH_RISK_FLAGS_PRESENT")),
                engine(FraudEngineType.ML_MODEL, FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        List.of("ML_MODEL_SIGNAL")),
                engine(FraudEngineType.VELOCITY, FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                        List.of("VELOCITY_FEATURES_UNAVAILABLE"))
        );
        List<EngineIntelligenceDiagnosticSignalProjection> signals = List.of(
                diagnosticSignal(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineType.RULES, "HIGH_RISK_FLAGS_PRESENT"),
                diagnosticSignal(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        FraudEngineType.ML_MODEL, "ML_MODEL_SIGNAL"),
                diagnosticSignal(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                        FraudEngineType.VELOCITY, "VELOCITY_FEATURES_UNAVAILABLE")
        );
        EngineIntelligenceFeedbackDatasetRecord record = mapper.map(
                feedback, alert, projectionWithSignals(engines, signals));

        assertThat(record.reasonCodes()).contains("HIGH_RISK_FLAGS_PRESENT", "ML_MODEL_SIGNAL",
                "VELOCITY_FEATURES_UNAVAILABLE");
        assertThat(record.diagnosticSignals()).contains("HIGH_RISK_FLAGS_PRESENT", "ML_MODEL_SIGNAL",
                "VELOCITY_FEATURES_UNAVAILABLE");

        for (String engineId : FraudEngineIdentityContract.engineOrder()) {
            FraudEngineType engineType = FraudEngineIdentityContract.expectedTypeFor(engineId).orElseThrow();
            for (String sharedReasonCode : EngineIntelligenceReasonCodePolicy.sharedOrchestratorReasonCodes()) {
                List<EngineIntelligenceEngineProjection> sharedEngineProfile = engineProfileWithReasonCodes(
                        engineId, List.of(sharedReasonCode));
                EngineIntelligenceDiagnosticSignalProjection sharedSignal = diagnosticSignalForEngine(
                        engineId, engineType, sharedReasonCode);
                EngineIntelligenceFeedbackDatasetRecord sharedRecord = mapper.map(
                        feedback,
                        alert,
                        projectionWithSignals(sharedEngineProfile, List.of(sharedSignal))
                );

                assertThat(sharedRecord.reasonCodes()).contains(sharedReasonCode);
                assertThat(sharedRecord.diagnosticSignals()).contains(sharedReasonCode);
            }
        }
    }

    @Test
    void rejectsUnknownOrMismatchedEngineIdentityForCodesAndSignals() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);

        assertCorruptedProjection(mapper, feedback, alert, projection(2, "txn-1", List.of(
                engine(FraudEngineType.ML_MODEL, FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        List.of("ML_MODEL_SIGNAL")),
                engine(FraudEngineType.ML_MODEL, FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        List.of("ML_MODEL_SIGNAL"))
        )));
        assertCorruptedProjection(mapper, feedback, alert, projection(2, "txn-1", List.of(
                engine(FraudEngineType.RULES, FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        List.of("HIGH_RISK_FLAGS_PRESENT")),
                engine(FraudEngineType.ML_MODEL, FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        List.of("ML_MODEL_SIGNAL")),
                engine(FraudEngineType.RULES, "unknown.engine", List.of("HIGH_RISK_FLAGS_PRESENT"))
        )));

        EngineIntelligenceProjection mismatchedSignal = projectionWithSignals(
                engineProfileWithReasonCodes(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        List.of("HIGH_RISK_FLAGS_PRESENT")),
                List.of(diagnosticSignal(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineType.ML_MODEL, "ML_MODEL_SIGNAL"))
        );
        assertCorruptedProjection(mapper, feedback, alert, mismatchedSignal);

        EngineIntelligenceProjection unknownSignal = projectionWithSignals(
                engineProfileWithReasonCodes(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        List.of("HIGH_RISK_FLAGS_PRESENT")),
                List.of(diagnosticSignal("unknown.engine", FraudEngineType.RULES, "HIGH_RISK_FLAGS_PRESENT"))
        );
        assertCorruptedProjection(mapper, feedback, alert, unknownSignal);
    }

    @Test
    void exportsTenElevenAndTwentyReasonCodeOccurrencesWithEngineIdentityAndOrder() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);

        assertReasonCodeProfile(mapper, feedback, alert, reasonCodeProfile(2, false), 10);
        assertReasonCodeProfile(mapper, feedback, alert, reasonCodeProfile(3, true), 11);
        EngineIntelligenceProjection fourEngineProjection = projectionWithSignals(
                reasonCodeProfile(4, false), List.of());
        EngineIntelligenceFeedbackDatasetRecord twentyCodeRecord = mapper.map(
                feedback, alert, fourEngineProjection);
        assertThat(twentyCodeRecord.reasonCodes()).hasSize(20);
        assertThat(twentyCodeRecord.reasonCodesByEngine().keySet())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(twentyCodeRecord.reasonCodes())
                .containsExactlyElementsOf(flattenReasonCodes(fourEngineProjection.getEngines()));
        assertThat(twentyCodeRecord.reasonCodes()).filteredOn("HIGH_RISK_FLAGS_PRESENT"::equals).hasSize(2);

        EngineIntelligenceFeedbackDatasetExportResult exported = exportWithProjection(fourEngineProjection);
        assertThat(exported.failed()).isFalse();
        String jsonlRecord = new EngineIntelligenceFeedbackDatasetJsonlExporter()
                .exportJsonl(exported)
                .lines()
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertThat(jsonlRecord)
                .contains("\"reasonCodesByEngine\":{\"rules.primary\":")
                .contains("\"device.primary\":[\"DEVICE_EVIDENCE_NOT_ADMITTED\"")
                .contains("\"reasonCodes\":[\"HIGH_RISK_FLAGS_PRESENT\",\"HIGH_RISK_FLAGS_PRESENT\"");
    }

    @Test
    void keepsDiagnosticSignalLimitAtFiveIndependentOfReasonCodeLimit() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);
        List<EngineIntelligenceEngineProjection> engines = reasonCodeProfile(4, false);
        List<EngineIntelligenceDiagnosticSignalProjection> fiveSignals = java.util.Collections.nCopies(
                5,
                diagnosticSignal(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        FraudEngineType.ML_MODEL, "ML_MODEL_SIGNAL")
        );

        EngineIntelligenceFeedbackDatasetRecord record = mapper.map(
                feedback, alert, projectionWithSignals(engines, fiveSignals));
        assertThat(record.reasonCodes()).hasSize(20);
        assertThat(record.diagnosticSignals()).hasSize(5);

        List<EngineIntelligenceDiagnosticSignalProjection> sixSignals = java.util.Collections.nCopies(
                6,
                diagnosticSignal(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        FraudEngineType.ML_MODEL, "ML_MODEL_SIGNAL")
        );
        assertCorruptedProjection(mapper, feedback, alert, projectionWithSignals(engines, sixSignals));
    }

    @Test
    void rejectsDuplicateAndOutOfOrderEngineProfiles() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);
        EngineIntelligenceEngineProjection rules = engine(
                FraudEngineType.RULES, FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID);
        EngineIntelligenceEngineProjection ml = engine(
                FraudEngineType.ML_MODEL, FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID);

        assertCorruptedProjection(mapper, feedback, alert,
                projection(2, "txn-1", List.of(ml, rules)));
        assertCorruptedProjection(mapper, feedback, alert,
                projection(2, "txn-1", List.of(rules, ml, ml)));
    }

    @Test
    void rejectsUnicodeRawAndOverlongReasonCodesWithBoundedFailure() {
        EngineIntelligenceFeedbackDatasetRecordMapper mapper = new EngineIntelligenceFeedbackDatasetRecordMapper();
        EngineIntelligenceFeedbackDocument feedback = feedback(
                "feedback-1", "txn-1", FROM.plusSeconds(1), List.of());
        AlertDocument alert = alert("txn-1", AnalystDecision.CONFIRMED_FRAUD);

        for (String reasonCode : List.of("DEVICE_ÉVIDENCE", "CUSTOMER_123_FLAGGED", "A".repeat(65))) {
            EngineIntelligenceProjection corrupted = projection(
                    2,
                    "txn-1",
                    engineProfileWithReasonCodes(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                            List.of(reasonCode))
            );
            assertCorruptedProjection(mapper, feedback, alert, corrupted);
        }
    }

    @Test
    void version2ProjectionRejectsUnknownDeviceReasonCode() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(
                v2DeviceProjection("DEVICE_CUSTOMER_123"));

        assertThat(result.failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
        assertThat(result.records()).isEmpty();
    }

    @Test
    void unsupportedProjectionContractVersionFailsClosed() {
        assertThat(exportWithProjection(projection(3, "txn-1", projectionEngines())).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void unsupportedProjectionVersionDoesNotCreateDatasetRecord() {
        EngineIntelligenceFeedbackDatasetExportResult result = exportWithProjection(projection(3, "txn-1", projectionEngines()));

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
        assertThat(exportWithProjection(projection(EngineIntelligenceSummary.CONTRACT_VERSION, "txn-other", projectionEngines())).failureReason())
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
                projection(EngineIntelligenceSummary.CONTRACT_VERSION, "txn-other", projectionEngines())
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
                EngineIntelligenceSummary.CONTRACT_VERSION,
                "txn-1",
                engineProfileWithReasonCodes(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                        List.of("deviceId:abc"))
        );

        assertThat(exportWithProjection(projection).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void tooManyProjectionReasonCodesFailsAsCorruptedProjection() {
        EngineIntelligenceProjection projection = projection(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                "txn-1",
                engineProfileWithReasonCodes(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, List.of(
                        "CODE_A", "CODE_B", "CODE_C", "CODE_D", "CODE_E", "CODE_F"
                ))
        );

        assertThat(exportWithProjection(projection).failureReason())
                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION);
    }

    @Test
    void unsafeProjectionDiagnosticSignalFailsAsCorruptedProjection() {
        EngineIntelligenceProjection projection = projection(
                EngineIntelligenceSummary.CONTRACT_VERSION,
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
                occurrence(),
                EngineIntelligenceSummary.CONTRACT_VERSION,
                FROM,
                null,
                null,
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

    @Test
    void serviceDoesNotLookupMoreThanRawRowsReadAlerts() {
        LookupFixture fixture = lookupFixture();

        EngineIntelligenceFeedbackDatasetExportResult result =
                service(fixture.feedbacks(), fixture.alerts(), fixture.projections()).export(request(10));

        assertThat(result.rawRowsRead()).isEqualTo(3);
        verify(fixture.alerts(), times(1)).findByTransactionId("txn-a");
        verify(fixture.alerts(), times(1)).findByTransactionId("txn-b");
        verify(fixture.alerts(), times(1)).findByTransactionId("txn-c");
    }

    @Test
    void serviceDoesNotLookupMoreThanRawRowsReadProjections() {
        LookupFixture fixture = lookupFixture();

        EngineIntelligenceFeedbackDatasetExportResult result =
                service(fixture.feedbacks(), fixture.alerts(), fixture.projections()).export(request(10));

        assertThat(result.rawRowsRead()).isEqualTo(3);
        verify(fixture.projections(), times(1)).findById("txn-a");
        verify(fixture.projections(), times(1)).findById("txn-b");
        verify(fixture.projections(), times(1)).findById("txn-c");
    }

    private EngineIntelligenceFeedbackDatasetExportResult exportWithOneRecord() {
        return service(defaultFeedbacks()).export(request(10));
    }

    private LookupFixture lookupFixture() {
        EngineIntelligenceFeedbackDatasetQueryRepository feedbacks = mock(EngineIntelligenceFeedbackDatasetQueryRepository.class);
        when(feedbacks.findBoundedBySubmittedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-a", FROM.plusSeconds(30)),
                feedback("feedback-b", "txn-b", FROM.plusSeconds(20)),
                feedback("feedback-c", "txn-c", FROM.plusSeconds(10))
        ));
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findByTransactionId("txn-a")).thenReturn(Optional.of(alert("txn-a", AnalystDecision.CONFIRMED_FRAUD)));
        when(alerts.findByTransactionId("txn-b")).thenReturn(Optional.of(alert("txn-b", AnalystDecision.MARKED_LEGITIMATE)));
        when(alerts.findByTransactionId("txn-c")).thenReturn(Optional.of(alert("txn-c", AnalystDecision.CONFIRMED_FRAUD)));
        EngineIntelligenceProjectionRepository projections = mock(EngineIntelligenceProjectionRepository.class);
        when(projections.findById("txn-a")).thenReturn(Optional.empty());
        when(projections.findById("txn-b")).thenReturn(Optional.empty());
        when(projections.findById("txn-c")).thenReturn(Optional.empty());
        return new LookupFixture(feedbacks, alerts, projections);
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
                List.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null
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
        return projection(EngineIntelligenceSummary.CONTRACT_VERSION, "txn-1", projectionEngines());
    }

    private EngineIntelligenceProjection v2DeviceProjection() {
        return v2DeviceProjection("DEVICE_SCORE_NOT_CALIBRATED");
    }

    private EngineIntelligenceProjection v2DeviceProjection(String deviceReasonCode) {
        return new EngineIntelligenceProjection(
                "txn-1",
                occurrence(),
                2,
                FROM,
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of("rules.primary", "ml.python.primary"),
                EngineIntelligenceAgreementStatus.DISAGREEMENT,
                EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                EngineIntelligenceScoreDeltaBucket.LARGE,
                List.of(
                        new EngineIntelligenceEngineProjection("rules.primary", FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")),
                        new EngineIntelligenceEngineProjection("ml.python.primary", FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE, RiskLevel.LOW, EngineIntelligenceScoreBucket.LOW,
                                List.of("LOW_MODEL_RISK"), new MlModelIdentity(
                                        "python-logistic-fraud-model", "2026-06-18.v1", "feature-contract-v2")),
                        new EngineIntelligenceEngineProjection("velocity.primary", FraudEngineType.VELOCITY,
                                FraudEngineStatus.SKIPPED, null, EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("VELOCITY_FEATURES_UNAVAILABLE")),
                        new EngineIntelligenceEngineProjection("device.primary", FraudEngineType.DEVICE_RISK,
                                FraudEngineStatus.DEGRADED, null, EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of(deviceReasonCode))
                ),
                List.of(new EngineIntelligenceDiagnosticSignalProjection("device.primary", FraudEngineType.DEVICE_RISK,
                        FraudEngineStatus.DEGRADED, EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL, null,
                        EngineIntelligenceScoreBucket.UNAVAILABLE, deviceReasonCode)),
                List.of(),
                FROM,
                FROM
        );
    }

    private EngineIntelligenceProjection projectionWithMlOnly() {
        return projection(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                "txn-1",
                List.of(
                        unavailableEngine(FraudEngineType.RULES, "rules.primary", "ORCHESTRATOR_ENGINE_TIMEOUT"),
                        engine(FraudEngineType.ML_MODEL, "ml.python.primary")
                ),
                "ML_MODEL_SIGNAL",
                EngineIntelligenceAgreementStatus.PARTIAL,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        );
    }

    private EngineIntelligenceProjection projectionWithRulesOnly() {
        return projection(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                "txn-1",
                List.of(
                        engine(FraudEngineType.RULES, "rules.primary"),
                        unavailableEngine(FraudEngineType.ML_MODEL, "ml.python.primary", "ML_MODEL_UNAVAILABLE")
                ),
                null,
                EngineIntelligenceAgreementStatus.PARTIAL,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        );
    }

    private List<EngineIntelligenceEngineProjection> projectionEngines() {
        return List.of(
                engine(FraudEngineType.RULES, "rules.primary"),
                engine(FraudEngineType.ML_MODEL, "ml.python.primary")
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
        return projection(
                contractVersion,
                transactionId,
                engines,
                diagnosticReasonCode,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.NONE
        );
    }

    private EngineIntelligenceProjection projection(
            int contractVersion,
            String transactionId,
            List<EngineIntelligenceEngineProjection> engines,
            String diagnosticReasonCode,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        return new EngineIntelligenceProjection(
                transactionId,
                occurrence(),
                contractVersion,
                FROM,
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of("rules.primary", "ml.python.primary"),
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket,
                engines,
                diagnosticReasonCode == null ? List.of() : List.of(new EngineIntelligenceDiagnosticSignalProjection(
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

    private EngineIntelligenceProjection projectionWithSignals(
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals
    ) {
        return new EngineIntelligenceProjection(
                "txn-1",
                occurrence(),
                EngineIntelligenceSummary.CONTRACT_VERSION,
                FROM,
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID),
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.NONE,
                engines,
                diagnosticSignals,
                List.of(),
                FROM,
                FROM
        );
    }

    private List<EngineIntelligenceEngineProjection> reasonCodeProfile(int engineCount, boolean elevenCodeProfile) {
        List<EngineIntelligenceEngineProjection> engines = new java.util.ArrayList<>();
        List<String> engineOrder = FraudEngineIdentityContract.engineOrder();
        for (int index = 0; index < engineCount; index++) {
            String engineId = engineOrder.get(index);
            List<String> reasonCodes = reasonCodesForEngine(engineId);
            if (elevenCodeProfile && FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID.equals(engineId)) {
                reasonCodes = List.of(reasonCodes.getFirst());
            }
            engines.add(engineProjectionForProfile(engineId, reasonCodes));
        }
        return List.copyOf(engines);
    }

    private List<EngineIntelligenceEngineProjection> engineProfileWithReasonCodes(
            String selectedEngineId,
            List<String> selectedReasonCodes
    ) {
        List<EngineIntelligenceEngineProjection> engines = new java.util.ArrayList<>();
        for (String engineId : FraudEngineIdentityContract.engineOrder()) {
            boolean required = FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID.equals(engineId)
                    || FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(engineId);
            if (required || selectedEngineId.equals(engineId)) {
                List<String> codes = selectedEngineId.equals(engineId)
                        ? selectedReasonCodes
                        : List.of(validReasonCodeFor(FraudEngineIdentityContract.expectedTypeFor(engineId).orElseThrow()));
                engines.add(engineProjectionForProfile(engineId, codes));
            }
        }
        return List.copyOf(engines);
    }

    private EngineIntelligenceEngineProjection engineProjectionForProfile(String engineId, List<String> reasonCodes) {
        FraudEngineType engineType = FraudEngineIdentityContract.expectedTypeFor(engineId).orElseThrow();
        if (FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engineId)) {
            return new EngineIntelligenceEngineProjection(
                    engineId,
                    engineType,
                    FraudEngineStatus.DEGRADED,
                    null,
                    EngineIntelligenceScoreBucket.UNAVAILABLE,
                    reasonCodes
            );
        }
        return engine(engineType, engineId, reasonCodes);
    }

    private List<String> reasonCodesForEngine(String engineId) {
        if (FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID.equals(engineId)) {
            return List.of(
                    "HIGH_RISK_FLAGS_PRESENT",
                    "HIGH_RISK_FLAGS_PRESENT",
                    "VELOCITY_THRESHOLD_EXCEEDED",
                    "AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED",
                    "RAPID_TRANSFER_PATTERN_MATCHED"
            );
        }
        if (FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(engineId)) {
            return List.of(
                    "ML_MODEL_SIGNAL",
                    "ML_MODEL_TIMEOUT",
                    "ML_MODEL_INVALID_RESPONSE",
                    "ML_SCORE_MISSING",
                    "ML_SCORE_OUT_OF_RANGE"
            );
        }
        if (FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID.equals(engineId)) {
            return List.of(
                    "VELOCITY_FEATURES_UNAVAILABLE",
                    "VELOCITY_FEATURE_TYPE_INVALID",
                    "VELOCITY_FEATURE_VALUE_INVALID",
                    "VELOCITY_FEATURES_INCONSISTENT",
                    "HIGH_VELOCITY"
            );
        }
        if (FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engineId)) {
            return List.of(
                    "DEVICE_EVIDENCE_NOT_ADMITTED",
                    "DEVICE_EVIDENCE_UNAVAILABLE",
                    "DEVICE_EVIDENCE_PARTIAL",
                    "DEVICE_EVIDENCE_UNTRUSTED",
                    "DEVICE_SCORE_NOT_CALIBRATED"
            );
        }
        throw new IllegalArgumentException("unknown engine fixture id");
    }

    private List<String> flattenReasonCodes(List<EngineIntelligenceEngineProjection> engines) {
        return engines.stream().flatMap(engine -> engine.reasonCodes().stream()).toList();
    }

    private void assertReasonCodeProfile(
            EngineIntelligenceFeedbackDatasetRecordMapper mapper,
            EngineIntelligenceFeedbackDocument feedback,
            AlertDocument alert,
            List<EngineIntelligenceEngineProjection> engines,
            int expectedOccurrenceCount
    ) {
        EngineIntelligenceFeedbackDatasetRecord record = mapper.map(
                feedback, alert, projectionWithSignals(engines, List.of()));
        assertThat(record.reasonCodes()).hasSize(expectedOccurrenceCount)
                .containsExactlyElementsOf(flattenReasonCodes(engines));
        assertThat(record.reasonCodesByEngine().keySet())
                .containsExactlyElementsOf(engines.stream().map(EngineIntelligenceEngineProjection::engineId).toList());
        for (EngineIntelligenceEngineProjection engine : engines) {
            assertThat(record.reasonCodesByEngine().get(engine.engineId())).containsExactlyElementsOf(engine.reasonCodes());
        }
    }

    private EngineIntelligenceDiagnosticSignalProjection diagnosticSignal(
            String engineId,
            FraudEngineType engineType,
            String reasonCode
    ) {
        return new EngineIntelligenceDiagnosticSignalProjection(
                engineId,
                engineType,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                reasonCode
        );
    }

    private EngineIntelligenceDiagnosticSignalProjection diagnosticSignalForEngine(
            String engineId,
            FraudEngineType engineType,
            String reasonCode
    ) {
        boolean device = FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engineId);
        return new EngineIntelligenceDiagnosticSignalProjection(
                engineId,
                engineType,
                device ? FraudEngineStatus.DEGRADED : FraudEngineStatus.AVAILABLE,
                device ? EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL : EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                device ? null : RiskLevel.HIGH,
                device ? EngineIntelligenceScoreBucket.UNAVAILABLE : EngineIntelligenceScoreBucket.HIGH,
                reasonCode
        );
    }

    private String validReasonCodeFor(FraudEngineType engineType) {
        return switch (engineType) {
            case RULES -> "HIGH_RISK_FLAGS_PRESENT";
            case ML_MODEL -> "ML_MODEL_SIGNAL";
            case VELOCITY -> "VELOCITY_FEATURES_UNAVAILABLE";
            case DEVICE_RISK -> "DEVICE_SCORE_NOT_CALIBRATED";
            default -> throw new IllegalArgumentException("engine type has no reason-code fixture");
        };
    }

    private void assertCorruptedProjection(
            EngineIntelligenceFeedbackDatasetRecordMapper mapper,
            EngineIntelligenceFeedbackDocument feedback,
            AlertDocument alert,
            EngineIntelligenceProjection projection
    ) {
        assertThatThrownBy(() -> mapper.map(feedback, alert, projection))
                .isInstanceOf(EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException.class)
                .hasMessage(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION.name());
    }

    private ScoringOccurrenceOwnership occurrence() {
        return new ScoringOccurrenceOwnership("event-dataset", FROM, "a".repeat(64));
    }

    private EngineIntelligenceEngineProjection engine(FraudEngineType engineType, String engineId) {
        return engine(engineType, engineId, List.of(validReasonCodeFor(engineType)));
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

    private EngineIntelligenceEngineProjection unavailableEngine(
            FraudEngineType engineType,
            String engineId,
            String reasonCode
    ) {
        return new EngineIntelligenceEngineProjection(
                engineId,
                engineType,
                FraudEngineStatus.UNAVAILABLE,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE,
                List.of(reasonCode)
        );
    }

    private record LookupFixture(
            EngineIntelligenceFeedbackDatasetQueryRepository feedbacks,
            AlertRepository alerts,
            EngineIntelligenceProjectionRepository projections
    ) {
    }
}
