package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.audit.AuditAction;
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
        assertThat(response.fraudScore()).isEqualTo(0.91d);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(response.agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.PARTIAL);
        assertThat(response.analystRecommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_REVIEW);
        assertThat(savedRecords).hasSize(1);

        verify(auditService).audit(
                eq(AuditAction.RECORD_FRAUD_FEEDBACK),
                eq(AuditResourceType.FRAUD_FEEDBACK),
                eq(response.feedbackId()),
                eq("corr-1"),
                eq("analyst-1"),
                eq(AuditOutcome.SUCCESS),
                eq(null),
                any()
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
    void rejectsInvalidReasonCodeFormat() {
        assertBadRequest(new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("bad-code"),
                null
        ), "FRAUD_FEEDBACK_REASON_CODE_INVALID");
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

    private void assertBadRequest(CreateFraudFeedbackRequest request, String reason) {
        assertThatThrownBy(() -> service.create("txn-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(400);
                    assertThat(statusException.getReason()).isEqualTo(reason);
                });
    }

    private CreateFraudFeedbackRequest request() {
        return new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("CUSTOMER_CONFIRMED_FRAUD"),
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
