package com.frauddetection.alert.engineintelligence.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceFeedbackDatasetExportServiceTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-01T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-06-01T12:00:00Z");

    private final EngineIntelligenceFeedbackRepository feedbackRepository =
            mock(EngineIntelligenceFeedbackRepository.class);
    private final EngineIntelligenceProjectionRepository projectionRepository =
            mock(EngineIntelligenceProjectionRepository.class);
    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final EngineIntelligenceFeedbackDatasetExportService service =
            new EngineIntelligenceFeedbackDatasetExportService(
                    feedbackRepository,
                    projectionRepository,
                    alertRepository,
                    objectMapper()
            );

    @Test
    void confirmedFraudMapsToPositiveEvaluationLabel() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(fullProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.of(alert(AnalystDecision.CONFIRMED_FRAUD, T2)));

        EngineIntelligenceFeedbackDatasetRecord record = service.export(FROM, TO, 25).getFirst();

        assertThat(record.feedbackLabel()).isEqualTo(EngineIntelligenceFeedbackDatasetLabel.POSITIVE);
        assertThat(record.analystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(record.labelSource()).isEqualTo(EngineIntelligenceFeedbackDatasetLabelSource.ANALYST_DECISION);
        assertThat(record.decidedAt()).isEqualTo(T2);
    }

    @Test
    void legitimateMapsToNegativeEvaluationLabel() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(fullProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.of(alert(AnalystDecision.MARKED_LEGITIMATE, T2)));

        EngineIntelligenceFeedbackDatasetRecord record = service.export(FROM, TO, 25).getFirst();

        assertThat(record.feedbackLabel()).isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NEGATIVE);
        assertThat(record.analystDecision()).isEqualTo(AnalystDecision.MARKED_LEGITIMATE);
    }

    @Test
    void inconclusiveFeedbackWithoutAnalystDecisionIsNonTrainingNeverNegative() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(fullProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        EngineIntelligenceFeedbackDatasetRecord record = service.export(FROM, TO, 25).getFirst();

        assertThat(record.feedbackLabel()).isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING);
        assertThat(record.feedbackLabel()).isNotEqualTo(EngineIntelligenceFeedbackDatasetLabel.NEGATIVE);
        assertThat(record.labelSource()).isEqualTo(EngineIntelligenceFeedbackDatasetLabelSource.NO_ANALYST_DECISION);
    }

    @Test
    void missingMlResultIsAllowedAndNotTreatedAsLowRisk() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(rulesOnlyProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        EngineIntelligenceFeedbackDatasetRecord record = service.export(FROM, TO, 25).getFirst();

        assertThat(record.ruleRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(record.ruleScoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.HIGH);
        assertThat(record.mlRiskLevel()).isNull();
        assertThat(record.mlScoreBucket()).isNull();
    }

    @Test
    void missingEngineIntelligenceProjectionIsExplicitAndDoesNotCrashOldTransaction() {
        whenFeedback(feedback("feedback-1", "txn-old", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of());
        when(alertRepository.findByTransactionId("txn-old")).thenReturn(Optional.empty());

        EngineIntelligenceFeedbackDatasetRecord record = service.export(FROM, TO, 25).getFirst();

        assertThat(record.engineIntelligenceProjectionStatus())
                .isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING);
        assertThat(record.engineAgreement()).isNull();
        assertThat(record.riskMismatch()).isNull();
        assertThat(record.reasonCodes()).isEmpty();
        assertThat(record.diagnosticSignals()).isEmpty();
    }

    @Test
    void exportOrderingIsDeterministicNewestFeedbackFirst() {
        whenFeedback(List.of(
                feedback("feedback-a", "txn-a", T1),
                feedback("feedback-c", "txn-c", T2),
                feedback("feedback-b", "txn-b", T3)
        ));
        when(projectionRepository.findAllById(any())).thenReturn(List.of());
        when(alertRepository.findByTransactionId(any())).thenReturn(Optional.empty());

        List<EngineIntelligenceFeedbackDatasetRecord> records = service.export(FROM, TO, 25);

        assertThat(records).extracting(EngineIntelligenceFeedbackDatasetRecord::transactionId)
                .containsExactly("txn-b", "txn-c", "txn-a");
        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("submittedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("feedbackId").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void emptyExportIsValid() {
        whenFeedback(List.of());

        assertThat(service.export(FROM, TO, 25)).isEmpty();
        assertThat(service.exportJsonl(FROM, TO, 25)).isEmpty();
    }

    @Test
    void maxRecordsIsEnforcedInRepositoryQuery() {
        whenFeedback(List.of(
                feedback("feedback-2", "txn-2", T2),
                feedback("feedback-1", "txn-1", T1)
        ));
        when(projectionRepository.findAllById(any())).thenReturn(List.of());
        when(alertRepository.findByTransactionId(any())).thenReturn(Optional.empty());

        List<EngineIntelligenceFeedbackDatasetRecord> records = service.export(FROM, TO, 1);

        assertThat(records).hasSize(1);
        assertThat(capturedPageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void invalidDateRangeAndBoundsAreRejected() {
        assertThatThrownBy(() -> service.export(TO, FROM, 25))
                .isInstanceOf(EngineIntelligenceFeedbackDatasetExportQueryException.class);
        assertThatThrownBy(() -> service.export(FROM, FROM.plus(DurationDays.days(32)), 25))
                .isInstanceOf(EngineIntelligenceFeedbackDatasetExportQueryException.class);
        assertThatThrownBy(() -> service.export(FROM, TO, 0))
                .isInstanceOf(EngineIntelligenceFeedbackDatasetExportQueryException.class);
        assertThatThrownBy(() -> service.export(FROM, TO, 501))
                .isInstanceOf(EngineIntelligenceFeedbackDatasetExportQueryException.class);
    }

    @Test
    void projectionReadFailureIsExplicitNotSilentlyEmpty() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any()))
                .thenThrow(new IllegalStateException("raw mongo token endpoint"));

        assertThatThrownBy(() -> service.export(FROM, TO, 25))
                .isInstanceOfSatisfying(
                        EngineIntelligenceFeedbackDatasetExportUnavailableException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.PROJECTION_STORE_UNAVAILABLE)
                )
                .hasMessageNotContaining("mongo")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("endpoint");
    }

    @Test
    void duplicateFeedbackUsesNewestSubmittedAtWithFeedbackIdTieBreak() {
        EngineIntelligenceFeedbackDocument older = feedback(
                "feedback-old",
                "txn-1",
                T1,
                EngineIntelligenceFeedbackUsefulness.NOT_HELPFUL
        );
        EngineIntelligenceFeedbackDocument newer = feedback(
                "feedback-new",
                "txn-1",
                T2,
                EngineIntelligenceFeedbackUsefulness.HELPFUL
        );
        whenFeedback(List.of(older, newer));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(fullProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        List<EngineIntelligenceFeedbackDatasetRecord> records = service.export(FROM, TO, 25);

        assertThat(records).singleElement()
                .satisfies(record -> {
                    assertThat(record.transactionId()).isEqualTo("txn-1");
                    assertThat(record.usefulness()).isEqualTo(EngineIntelligenceFeedbackUsefulness.HELPFUL);
                    assertThat(record.feedbackCreatedAt()).isEqualTo(T2);
                });
    }

    @Test
    void jsonlLinesAreStableAndDoNotExposeRawSensitiveFields() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        when(projectionRepository.findAllById(any())).thenReturn(List.of(fullProjection("txn-1")));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.of(alert(AnalystDecision.CONFIRMED_FRAUD, T2)));

        String first = service.exportJsonl(FROM, TO, 25);
        String second = service.exportJsonl(FROM, TO, 25);

        assertThat(first).isEqualTo(second);
        assertThat(first.lines()).hasSize(1);
        assertThat(first)
                .contains("\"transactionId\":\"txn-1\"")
                .contains("\"feedbackLabel\":\"POSITIVE\"")
                .contains("\"engineIntelligenceProjectionStatus\":\"PRESENT\"");
        assertThat(first).doesNotContain(
                "customerId",
                "accountId",
                "cardId",
                "deviceId",
                "merchantId",
                "PAN",
                "IBAN",
                "email",
                "phone",
                "submittedBy",
                "analyst-raw-secret",
                "correlationId",
                "corr-raw-secret",
                "idempotencyKeyHash",
                "idempotency-raw-secret",
                "requestPayloadHash",
                "request-payload-raw-secret",
                "rawPayload",
                "rawEvidence",
                "featureVector",
                "stackTrace",
                "exceptionMessage",
                "token",
                "secret",
                "endpoint",
                "groundTruth",
                "modelTrainingLabel"
        );
    }

    @Test
    void corruptedProjectionReasonCodeIsRejectedInsteadOfExported() {
        whenFeedback(feedback("feedback-1", "txn-1", T1));
        EngineIntelligenceProjection projection = new EngineIntelligenceProjection(
                "txn-1",
                1,
                T1,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.SMALL,
                List.of(new EngineIntelligenceEngineProjection(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("rawEvidence")
                )),
                List.of(),
                List.of(),
                T1,
                T1
        );
        when(projectionRepository.findAllById(any())).thenReturn(List.of(projection));
        when(alertRepository.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportJsonl(FROM, TO, 25))
                .isInstanceOfSatisfying(
                        EngineIntelligenceFeedbackDatasetExportUnavailableException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_SOURCE_DATA)
                );
    }

    private void whenFeedback(EngineIntelligenceFeedbackDocument document) {
        whenFeedback(List.of(document));
    }

    private void whenFeedback(List<EngineIntelligenceFeedbackDocument> documents) {
        when(feedbackRepository.findByCreatedAtBetween(eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(documents);
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(feedbackRepository).findByCreatedAtBetween(eq(FROM), eq(TO), pageable.capture());
        return pageable.getValue();
    }

    private EngineIntelligenceFeedbackDocument feedback(String feedbackId, String transactionId, Instant submittedAt) {
        return feedback(feedbackId, transactionId, submittedAt, EngineIntelligenceFeedbackUsefulness.HELPFUL);
    }

    private EngineIntelligenceFeedbackDocument feedback(
            String feedbackId,
            String transactionId,
            Instant submittedAt,
            EngineIntelligenceFeedbackUsefulness usefulness
    ) {
        return new EngineIntelligenceFeedbackDocument(
                feedbackId,
                transactionId,
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                usefulness,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-raw-secret",
                submittedAt,
                "corr-raw-secret",
                "idempotency-raw-secret",
                "request-payload-raw-secret",
                submittedAt
        );
    }

    private EngineIntelligenceProjection fullProjection(String transactionId) {
        return projection(
                transactionId,
                List.of(
                        engine("rules.primary", FraudEngineType.RULES, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        engine("ml.python.primary", FraudEngineType.ML_MODEL, RiskLevel.MEDIUM, EngineIntelligenceScoreBucket.MEDIUM)
                ),
                List.of(new EngineIntelligenceDiagnosticSignalProjection(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        "HIGH_VELOCITY"
                ))
        );
    }

    private EngineIntelligenceProjection rulesOnlyProjection(String transactionId) {
        return projection(
                transactionId,
                List.of(engine("rules.primary", FraudEngineType.RULES, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)),
                List.of()
        );
    }

    private EngineIntelligenceProjection projection(
            String transactionId,
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> signals
    ) {
        return new EngineIntelligenceProjection(
                transactionId,
                1,
                T1,
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.SMALL,
                engines,
                signals,
                List.of(),
                T1,
                T1
        );
    }

    private EngineIntelligenceEngineProjection engine(
            String engineId,
            FraudEngineType engineType,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket
    ) {
        return new EngineIntelligenceEngineProjection(
                engineId,
                engineType,
                FraudEngineStatus.AVAILABLE,
                riskLevel,
                scoreBucket,
                List.of("HIGH_VELOCITY")
        );
    }

    private AlertDocument alert(AnalystDecision decision, Instant decidedAt) {
        AlertDocument document = new AlertDocument();
        document.setAnalystDecision(decision);
        document.setDecidedAt(decidedAt);
        document.setCustomerId("customer-raw-secret");
        document.setCorrelationId("alert-corr-raw-secret");
        document.setAnalystId("analyst-raw-secret");
        document.setDecisionReason("raw decision reason secret");
        return document;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static final class DurationDays {

        private DurationDays() {
        }

        private static java.time.Duration days(long days) {
            return java.time.Duration.ofDays(days);
        }
    }
}
