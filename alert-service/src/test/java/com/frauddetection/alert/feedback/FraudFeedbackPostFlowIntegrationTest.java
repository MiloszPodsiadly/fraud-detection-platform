package com.frauddetection.alert.feedback;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxService;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.config.SecurityDeniedAccessTelemetrySliceTestConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudFeedbackController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
        AlertServiceExceptionHandler.class,
        FraudFeedbackService.class,
        FraudFeedbackMapper.class,
        EngineIntelligenceResponseMapper.class
})
class FraudFeedbackPostFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudFeedbackRepository repository;

    @MockitoBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @MockitoBean
    private EngineIntelligenceReadService engineIntelligenceReadService;

    @MockitoBean
    private CurrentAnalystUser currentAnalystUser;

    @MockitoBean
    private WriteActionAuditOutboxService auditOutboxService;

    @MockitoBean
    private RegulatedMutationTransactionRunner transactionRunner;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    private final List<FraudFeedbackRecord> savedRecords = new ArrayList<>();

    @BeforeEach
    void setUp() {
        savedRecords.clear();
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(transactionRunner.mode()).thenReturn(RegulatedMutationTransactionMode.OFF);
        when(transactionMonitoringUseCase.getScoredTransaction("txn-1")).thenReturn(scoredTransaction());
        when(engineIntelligenceReadService.read("txn-1")).thenReturn(projectedEngineIntelligence());
        when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                "analyst-1",
                Set.of(),
                Set.of(AnalystAuthority.FRAUD_FEEDBACK_WRITE)
        )));
        when(repository.save(any(FraudFeedbackRecord.class))).thenAnswer(invocation -> {
            FraudFeedbackRecord record = invocation.getArgument(0);
            savedRecords.add(record);
            return record;
        });
    }

    @Test
    void postSuccessPersistsFeedbackAndOutboxIntentBeforeReturning201() throws Exception {
        String body = mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestWithNotes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.feedbackLabel").value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.feedbackStatus").value("RECORDED"))
                .andExpect(jsonPath("$.createdBy").value("analyst-1"))
                .andExpect(jsonPath("$.notesPresent").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(savedRecords).hasSize(1);
        FraudFeedbackRecord saved = savedRecords.getFirst();
        assertThat(saved.getFeedbackId()).startsWith("ffb-");
        assertThat(saved.getNotes()).isEqualTo("Customer confirmed fraud");
        assertThat(body).doesNotContain("Customer confirmed fraud", "rawMlRequest", "rawFeatureVector", "rawEvidence");

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditOutboxService).createPendingAudit(
                eq("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:" + saved.getFeedbackId()),
                eq(AuditAction.RECORD_FRAUD_FEEDBACK),
                eq(AuditResourceType.FRAUD_FEEDBACK),
                eq(saved.getFeedbackId()),
                eq("corr-1"),
                eq("analyst-1"),
                eq(AuditOutcome.SUCCESS),
                metadata.capture()
        );
        assertThat(metadata.getValue().filtersSummary())
                .contains("transactionId=txn-1", "feedbackLabel=CONFIRMED_FRAUD", "status=RECORDED")
                .doesNotContain("Customer confirmed fraud", "rawMlRequest", "rawFeatureVector", "rawEvidence", "token", "secret");
    }

    @Test
    void postDuplicateReturns409BeforeSaveOrOutbox() throws Exception {
        when(repository.existsByTransactionId("txn-1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_ALREADY_RECORDED"));

        verify(repository, never()).save(any());
        verify(auditOutboxService, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void postMissingActorFailsClosedBeforeDuplicateLookupSaveOrOutbox() throws Exception {
        when(currentAnalystUser.get()).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_ACTOR_REQUIRED"));

        verify(repository, never()).existsByTransactionId("txn-1");
        verify(repository, never()).save(any());
        verify(auditOutboxService, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void postOutboxPersistenceFailureReturns503AndCompensatesSavedFeedback() throws Exception {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(auditOutboxService).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE"));

        assertThat(savedRecords).hasSize(1);
        verify(repository).deleteById(savedRecords.getFirst().getFeedbackId());
    }

    @Test
    void postDuplicateKeyRaceReturns409AndDoesNotCreateOutbox() throws Exception {
        when(repository.save(any(FraudFeedbackRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_ALREADY_RECORDED"));

        verify(auditOutboxService, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void postDecisionLabelMismatchReturns400BeforeSaveOrOutbox() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "analystDecision": "MARKED_FRAUD",
                                  "feedbackLabel": "CONFIRMED_LEGITIMATE",
                                  "decisionReasonCodes": ["CUSTOMER_CONFIRMED_FRAUD"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH"));

        verify(repository, never()).save(any());
        verify(auditOutboxService, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void postEngineIntelligenceSnapshotFailureStillPersistsFeedbackAndOutbox() throws Exception {
        when(engineIntelligenceReadService.read("txn-1")).thenThrow(new EngineIntelligenceProjectionReadUnavailableException());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.engineIntelligenceStatus").value("UNAVAILABLE"));

        assertThat(savedRecords).hasSize(1);
        verify(auditOutboxService).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private RequestPostProcessor userWith(String authority) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                "n/a",
                List.of(new SimpleGrantedAuthority(authority))
        ));
    }

    private String validRequest() {
        return """
                {
                  "analystDecision": "MARKED_FRAUD",
                  "feedbackLabel": "CONFIRMED_FRAUD",
                  "decisionReasonCodes": ["CUSTOMER_CONFIRMED_FRAUD"]
                }
                """;
    }

    private String validRequestWithNotes() {
        return """
                {
                  "analystDecision": "MARKED_FRAUD",
                  "feedbackLabel": "CONFIRMED_FRAUD",
                  "decisionReasonCodes": ["CUSTOMER_CONFIRMED_FRAUD"],
                  "notes": "Customer confirmed fraud"
                }
                """;
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
}
