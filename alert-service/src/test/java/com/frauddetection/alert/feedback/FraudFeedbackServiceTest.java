package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationConfidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationNonDecisioning;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.common.events.recommendation.AnalystRecommendationSource;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudFeedbackServiceTest {

    private final FraudFeedbackRepository repository = mock(FraudFeedbackRepository.class);
    private final TransactionMonitoringUseCase transactionMonitoringUseCase = mock(TransactionMonitoringUseCase.class);
    private final EngineIntelligenceReadService engineIntelligenceReadService = mock(EngineIntelligenceReadService.class);
    private final CurrentAnalystUser currentAnalystUser = mock(CurrentAnalystUser.class);
    private final AuditService auditService = mock(AuditService.class);
    private final List<FraudFeedbackRecord> savedRecords = new ArrayList<>();

    private FraudFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FraudFeedbackService(
                repository,
                new FraudFeedbackMapper(),
                transactionMonitoringUseCase,
                engineIntelligenceReadService,
                new EngineIntelligenceResponseMapper(),
                currentAnalystUser,
                auditService,
                Clock.fixed(Instant.parse("2026-06-25T10:15:30Z"), ZoneOffset.UTC)
        );
        when(transactionMonitoringUseCase.getScoredTransaction("txn-1")).thenReturn(scoredTransaction());
        when(engineIntelligenceReadService.read("txn-1")).thenReturn(projectedEngineIntelligence());
        when(currentAnalystUser.get()).thenReturn(Optional.of(new com.frauddetection.alert.security.principal.AnalystPrincipal(
                "analyst-1",
                java.util.Set.of(),
                java.util.Set.of("fraud-feedback:write")
        )));
        when(repository.save(any(FraudFeedbackRecord.class))).thenAnswer(invocation -> {
            FraudFeedbackRecord record = invocation.getArgument(0);
            savedRecords.add(record);
            return record;
        });
    }

    @Test
    void createsFeedbackWithBoundedSnapshotsAndAudit() {
        FraudFeedbackResponse response = service.create("txn-1", request());

        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.feedbackLabel()).isEqualTo(FraudFeedbackLabel.CONFIRMED_FRAUD);
        assertThat(response.labelSource()).isEqualTo(FeedbackLabelSource.ANALYST_REVIEW);
        assertThat(response.feedbackStatus()).isEqualTo(FraudFeedbackStatus.RECORDED);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-06-25T10:15:30Z"));
        assertThat(response.createdBy()).isEqualTo("analyst-1");
        assertThat(response.notesPresent()).isTrue();
        assertThat(response.fraudScore()).isEqualTo(0.91d);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(response.agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.PARTIAL);
        assertThat(response.analystRecommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_REVIEW);
        assertThat(savedRecords).hasSize(1);

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.RECORD_FRAUD_FEEDBACK),
                eq(AuditResourceType.FRAUD_FEEDBACK),
                eq(response.feedbackId()),
                eq("corr-1"),
                eq("analyst-1"),
                eq(AuditOutcome.SUCCESS),
                eq(null),
                metadata.capture()
        );
        assertThat(metadata.getValue().filtersSummary())
                .contains("transactionId=txn-1")
                .contains("feedbackLabel=CONFIRMED_FRAUD")
                .contains("status=RECORDED")
                .doesNotContain(
                        "Customer confirmed fraud",
                        "rawMlRequest",
                        "rawFeatureVector",
                        "rawEvidence",
                        "token",
                        "secret"
                );
    }

    @Test
    void duplicateFeedbackReturns409AndDoesNotOverwriteOriginal() {
        when(repository.existsByTransactionId("txn-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("txn-1", request()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(409);

        verify(repository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readsExistingFeedback() {
        FraudFeedbackRecord record = record();
        when(repository.findByTransactionId("txn-1")).thenReturn(Optional.of(record));

        FraudFeedbackResponse response = service.get("txn-1");

        assertThat(response.feedbackId()).isEqualTo("feedback-1");
        assertThat(response.feedbackLabel()).isEqualTo(FraudFeedbackLabel.CONFIRMED_LEGITIMATE);
        assertThat(response.notesPresent()).isFalse();
    }

    @Test
    void noFeedbackReadReturns404() {
        when(repository.findByTransactionId("txn-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("txn-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void rejectsTooManyReasonCodes() {
        CreateFraudFeedbackRequest invalid = new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                java.util.Collections.nCopies(11, "CODE"),
                null
        );

        assertBadRequest(invalid, "FRAUD_FEEDBACK_REASON_CODES_TOO_MANY");
    }

    @Test
    void rejectsDecisionLabelMismatches() {
        assertBadRequest(request(AnalystDecision.MARKED_FRAUD, FraudFeedbackLabel.CONFIRMED_LEGITIMATE),
                "FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
        assertBadRequest(request(AnalystDecision.MARKED_LEGITIMATE, FraudFeedbackLabel.CONFIRMED_FRAUD),
                "FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
        assertBadRequest(request(AnalystDecision.MARKED_INCONCLUSIVE, FraudFeedbackLabel.CONFIRMED_LEGITIMATE),
                "FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
        assertBadRequest(request(AnalystDecision.REQUESTED_MORE_INFO, FraudFeedbackLabel.CONFIRMED_FRAUD),
                "FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
    }

    @Test
    void acceptsValidDecisionLabelPairs() {
        assertAccepted(request(AnalystDecision.MARKED_FRAUD, FraudFeedbackLabel.CONFIRMED_FRAUD));
        assertAccepted(request(AnalystDecision.MARKED_LEGITIMATE, FraudFeedbackLabel.CONFIRMED_LEGITIMATE));
        assertAccepted(request(AnalystDecision.MARKED_INCONCLUSIVE, FraudFeedbackLabel.INCONCLUSIVE));
        assertAccepted(request(AnalystDecision.REQUESTED_MORE_INFO, FraudFeedbackLabel.NEEDS_MORE_INFO));
    }

    @Test
    void acceptsAllowlistedReasonCodes() {
        assertAccepted(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("CUSTOMER_CONFIRMED_FRAUD", "ANALYST_CONFIRMED_FRAUD"),
                null
        ));
    }

    @Test
    void validatesReasonCodesAgainstFeedbackLabel() {
        assertReasonCodeAccepted(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                "CUSTOMER_CONFIRMED_FRAUD"
        );
        assertReasonCodeAccepted(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                "ANALYST_CONFIRMED_FRAUD"
        );
        assertReasonCodeMismatch(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                "CUSTOMER_CONFIRMED_LEGITIMATE"
        );
        assertReasonCodeAccepted(
                AnalystDecision.MARKED_LEGITIMATE,
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                "CUSTOMER_CONFIRMED_LEGITIMATE"
        );
        assertReasonCodeMismatch(
                AnalystDecision.MARKED_LEGITIMATE,
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                "CUSTOMER_CONFIRMED_FRAUD"
        );
        assertReasonCodeAccepted(
                AnalystDecision.MARKED_INCONCLUSIVE,
                FraudFeedbackLabel.INCONCLUSIVE,
                "INSUFFICIENT_EVIDENCE"
        );
        assertReasonCodeMismatch(
                AnalystDecision.MARKED_INCONCLUSIVE,
                FraudFeedbackLabel.INCONCLUSIVE,
                "CUSTOMER_CONFIRMED_FRAUD"
        );
        assertReasonCodeAccepted(
                AnalystDecision.REQUESTED_MORE_INFO,
                FraudFeedbackLabel.NEEDS_MORE_INFO,
                "NEEDS_CUSTOMER_CONTACT"
        );
        assertReasonCodeAccepted(
                AnalystDecision.REQUESTED_MORE_INFO,
                FraudFeedbackLabel.NEEDS_MORE_INFO,
                "ANALYST_NEEDS_MORE_INFO"
        );
        assertReasonCodeMismatch(
                AnalystDecision.REQUESTED_MORE_INFO,
                FraudFeedbackLabel.NEEDS_MORE_INFO,
                "CHARGEBACK_SIGNAL"
        );
    }

    @Test
    void rejectsUnknownReasonCodes() {
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("UNKNOWN_REASON"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_UNKNOWN");
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("RANDOM_REASON"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_UNKNOWN");
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("FRAUD_123"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_UNKNOWN");
    }

    @Test
    void rejectsInvalidReasonCodeFormat() {
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("bad-code"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_INVALID");
    }

    @Test
    void rejectsUnsafeReasonCode() {
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("RAW_EVIDENCE"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_UNSAFE");
    }

    @Test
    void rejectsUnsafeNotes() {
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("CUSTOMER_CONFIRMED_FRAUD"),
                "rawMlRequest was pasted"
        ), "FRAUD_FEEDBACK_NOTES_UNSAFE");
    }

    @Test
    void projectionReadFailureIsStoredAsUnavailable() {
        when(engineIntelligenceReadService.read("txn-1")).thenThrow(new EngineIntelligenceProjectionReadUnavailableException());

        FraudFeedbackResponse response = service.create("txn-1", request());

        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.agreementStatus()).isNull();
    }

    @Test
    void missingCurrentAnalystUserFailsClosedBeforeSaveAndAudit() {
        when(currentAnalystUser.get()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("txn-1", request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(403);
                    assertThat(statusException.getReason()).isEqualTo("FRAUD_FEEDBACK_ACTOR_REQUIRED");
                });

        verify(repository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingCurrentAnalystUserFailsClosedBeforeDuplicateLookup() {
        when(currentAnalystUser.get()).thenReturn(Optional.empty());
        when(repository.existsByTransactionId("txn-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("txn-1", request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(403);
                    assertThat(statusException.getReason()).isEqualTo("FRAUD_FEEDBACK_ACTOR_REQUIRED");
                });

        verify(repository, never()).existsByTransactionId("txn-1");
        verify(repository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateKeyRaceReturns409AndDoesNotAuditSuccess() {
        when(repository.existsByTransactionId("txn-1")).thenReturn(false);
        when(repository.save(any(FraudFeedbackRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create("txn-1", request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(409);
                    assertThat(statusException.getReason()).isEqualTo("FRAUD_FEEDBACK_ALREADY_RECORDED");
                });

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void responseUsesNotesPresentWithoutRawNotes() {
        FraudFeedbackResponse response = service.create("txn-1", request());

        assertThat(response.notesPresent()).isTrue();
        assertThat(response.toString()).doesNotContain("Customer confirmed fraud");
    }

    private void assertBadRequest(CreateFraudFeedbackRequest request, String reason) {
        assertThatThrownBy(() -> service.create("txn-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(400);
                    assertThat(statusException.getReason()).isEqualTo(reason);
                });
    }

    private void assertAccepted(CreateFraudFeedbackRequest request) {
        savedRecords.clear();
        service.create("txn-1", request);
        assertThat(savedRecords).hasSize(1);
    }

    private void assertReasonCodeAccepted(
            AnalystDecision analystDecision,
            FraudFeedbackLabel feedbackLabel,
            String reasonCode
    ) {
        assertAccepted(new CreateFraudFeedbackRequest(
                analystDecision,
                feedbackLabel,
                List.of(reasonCode),
                null
        ));
    }

    private void assertReasonCodeMismatch(
            AnalystDecision analystDecision,
            FraudFeedbackLabel feedbackLabel,
            String reasonCode
    ) {
        assertBadRequest(new CreateFraudFeedbackRequest(
                analystDecision,
                feedbackLabel,
                List.of(reasonCode),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_LABEL_MISMATCH");
    }

    private CreateFraudFeedbackRequest request() {
        return request(AnalystDecision.MARKED_FRAUD, FraudFeedbackLabel.CONFIRMED_FRAUD);
    }

    private CreateFraudFeedbackRequest request(AnalystDecision decision, FraudFeedbackLabel label) {
        String reasonCode = switch (decision) {
            case MARKED_FRAUD -> "ANALYST_CONFIRMED_FRAUD";
            case MARKED_LEGITIMATE -> "ANALYST_CONFIRMED_LEGITIMATE";
            case MARKED_INCONCLUSIVE -> "ANALYST_INCONCLUSIVE";
            case REQUESTED_MORE_INFO -> "ANALYST_NEEDS_MORE_INFO";
        };
        return new CreateFraudFeedbackRequest(
                decision,
                label,
                List.of(reasonCode),
                "Customer confirmed fraud"
        );
    }

    private ScoredTransaction scoredTransaction() {
        return new ScoredTransaction(
                "txn-1",
                "customer-1",
                "corr-1",
                Instant.parse("2026-06-25T09:00:00Z"),
                Instant.parse("2026-06-25T09:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                new AnalystRecommendationResult(
                        AnalystRecommendationStatus.AVAILABLE,
                        AnalystRecommendation.RECOMMEND_REVIEW,
                        AnalystRecommendationResult.RECOMMENDATION_VERSION,
                        Instant.parse("2026-06-25T09:00:02Z"),
                        AnalystRecommendationConfidence.MEDIUM,
                        AnalystRecommendationSource.RULES_RISK,
                        List.of("RULES_CRITICAL_RISK"),
                        List.of(),
                        AnalystRecommendationNonDecisioning.advisoryOnly()
                )
        );
    }

    private EngineIntelligenceReadModel projectedEngineIntelligence() {
        return EngineIntelligenceReadModel.projected(
                "txn-1",
                1,
                Instant.parse("2026-06-25T09:00:03Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private FraudFeedbackRecord record() {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setFeedbackId("feedback-1");
        record.setTransactionId("txn-1");
        record.setFeedbackLabel(FraudFeedbackLabel.CONFIRMED_LEGITIMATE);
        record.setAnalystDecision(AnalystDecision.MARKED_LEGITIMATE);
        record.setLabelSource(FeedbackLabelSource.ANALYST_REVIEW);
        record.setFeedbackStatus(FraudFeedbackStatus.RECORDED);
        record.setCreatedAt(Instant.parse("2026-06-25T10:15:30Z"));
        record.setDecisionReasonCodes(List.of());
        return record;
    }
}
