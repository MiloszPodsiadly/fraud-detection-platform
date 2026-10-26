package com.frauddetection.alert.controller;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.assistant.AnalystCaseSummaryResponse;
import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.assistant.CustomerRecentBehaviorSummary;
import com.frauddetection.alert.assistant.FraudReasonSummary;
import com.frauddetection.alert.assistant.RecommendedNextAction;
import com.frauddetection.alert.assistant.TransactionSummary;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.read.ReadAccessAuditEventDocument;
import com.frauddetection.alert.audit.read.ReadAccessAuditFailureInterceptor;
import com.frauddetection.alert.audit.read.ReadAccessAuditRepository;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditWebConfig;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.audit.read.SensitiveReadAuditPolicy;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AlertController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AlertResponseMapper.class, AlertServiceExceptionHandler.class, ReadAccessAuditClassifier.class,
        ReadAccessResultCountExtractor.class, ReadAccessAuditService.class, ReadAccessAuditResponseAdvice.class,
        ReadAccessAuditFailureInterceptor.class, ReadAccessAuditWebConfig.class})
@Tag("production-readiness")
@Tag("integration")
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockitoBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @MockitoBean
    private ReadAccessAuditRepository readAccessAuditRepository;

    @MockitoBean
    private CurrentAnalystUser currentAnalystUser;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockitoBean
    private SensitiveReadAuditPolicy sensitiveReadAuditPolicy;

    @BeforeEach
    void configureReadAudit() {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(false);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "analyst-1", java.util.Set.of(AnalystRole.ANALYST), AnalystRole.ANALYST.authorities(),
                "https://issuer.example#analyst-1")));
    }

    @Test
    void shouldListAlerts() throws Exception {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sampleAlert())));

        mockMvc.perform(get("/api/v1/alerts").queryParam("page", "1").queryParam("size", "5")
                        .header("X-Correlation-Id", "corr-alert-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].alertId").value("alert-1"))
                .andExpect(jsonPath("$.totalElements").value(1));

        org.mockito.ArgumentCaptor<ReadAccessAuditEventDocument> event =
                org.mockito.ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        org.mockito.Mockito.verify(readAccessAuditRepository).save(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().endpointCategory())
                .isEqualTo(ReadAccessEndpointCategory.ALERT_LIST);
        org.assertj.core.api.Assertions.assertThat(event.getValue().actorId()).isNotEqualTo("unknown");
        org.assertj.core.api.Assertions.assertThat(event.getValue().resultCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(event.getValue().page()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(event.getValue().size()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(event.getValue().correlationId()).isEqualTo("corr-alert-list");
        org.assertj.core.api.Assertions.assertThat(event.getValue().toString()).doesNotContain("cust-1", "txn-1");
    }

    @Test
    void shouldReturnAssistantSummary() throws Exception {
        when(analystCaseSummaryUseCase.generateSummary(org.mockito.ArgumentMatchers.any()))
                .thenReturn(sampleAssistantSummary());

        mockMvc.perform(get("/api/v1/alerts/alert-1/assistant-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value("alert-1"))
                .andExpect(jsonPath("$.mainFraudReasons[0].reasonCode").value("DEVICE_NOVELTY"))
                .andExpect(jsonPath("$.recommendedNextAction.actionCode").value("STEP_UP_REVIEW"));

        org.mockito.ArgumentCaptor<ReadAccessAuditEventDocument> event =
                org.mockito.ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        org.mockito.Mockito.verify(readAccessAuditRepository).save(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().endpointCategory())
                .isEqualTo(ReadAccessEndpointCategory.ALERT_ASSISTANT_SUMMARY);
        org.assertj.core.api.Assertions.assertThat(event.getValue().resourceId()).isEqualTo("alert-1");
        org.assertj.core.api.Assertions.assertThat(event.getValue().resultCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(event.getValue().toString())
                .doesNotContain("cust-1", "txn-1", "Device differs from known behavior.");
    }

    @Test
    void strictAuditFailureSuppressesRealAlertEndpointBodies() throws Exception {
        when(alertManagementUseCase.listAlerts(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleAlert())));
        when(analystCaseSummaryUseCase.generateSummary(org.mockito.ArgumentMatchers.any()))
                .thenReturn(sampleAssistantSummary());
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private mongo endpoint detail"))
                .when(readAccessAuditRepository).save(any());

        var list = mockMvc.perform(get("/api/v1/alerts")).andReturn();
        org.assertj.core.api.Assertions.assertThat(list.getResponse().getStatus())
                .as(list.getResponse().getContentAsString()).isEqualTo(503);
        var summary = mockMvc.perform(get("/api/v1/alerts/alert-1/assistant-summary")).andReturn();
        org.assertj.core.api.Assertions.assertThat(summary.getResponse().getStatus())
                .as(summary.getResponse().getContentAsString()).isEqualTo(503);

        org.assertj.core.api.Assertions.assertThat(list.getResponse().getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("cust-1", "txn-1", "private mongo endpoint detail");
        org.assertj.core.api.Assertions.assertThat(summary.getResponse().getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("cust-1", "txn-1", "Device differs from known behavior.",
                        "private mongo endpoint detail");
        org.mockito.Mockito.verify(readAccessAuditRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void shouldSubmitAnalystDecision() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-1")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-1",
                        Instant.parse("2026-04-20T10:00:00Z"),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                ));

        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc", "velocity"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.operation_status").value("FINALIZED_EVIDENCE_PENDING_EXTERNAL"));
    }

    @Test
    void shouldReturn202ForInProgressRegulatedDecisionCommand() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-1")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        null,
                        null,
                        SubmitDecisionOperationStatus.IN_PROGRESS
                ));

        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc", "velocity"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("IN_PROGRESS"));
    }

    @Test
    void recoveryRequiredWithSnapshot_mustNotReplayCommittedSuccess() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-1")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.RECOVERY_REQUIRED
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-1")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void finalizeRecoveryRequiredWithSnapshot_mustNotReplayFinalizedSuccess() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-1")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-1")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("FINALIZE_RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void renewalBudgetExceededRecovery_mustReturnExplicitRecovery() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-budget")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.RECOVERY_REQUIRED
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-budget")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")));
    }

    @Test
    void staleCheckpointFailure_mustNotReturnSuccess() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-stale")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-stale")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("FINALIZE_RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void longRunningProcessing_mustBeObservableNotSuccess() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-processing")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.IN_PROGRESS
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-processing")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")));
    }

    @Test
    void expiredProcessingWithoutProof_mustNotReturnCommittedSuccess() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-expired-processing")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-expired-processing")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("FINALIZE_RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void shouldReturn503WhenAuditPersistenceFailsBeforeWrite() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-1")))
                .thenThrow(new AuditPersistenceUnavailableException());

        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc", "velocity"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-1")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Audit persistence is unavailable; mutation was not executed."))
                .andExpect(jsonPath("$.details[0]").value("reason:REJECTED_EVIDENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.operation_status").doesNotExist());
    }

    @Test
    void shouldRejectMissingIdempotencyKeyForAnalystDecision() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new com.frauddetection.alert.regulated.MissingIdempotencyKeyException());

        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc", "velocity"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void shouldRejectInvalidAnalystDecisionWithoutLeakingBindingInternals() throws Exception {
        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst 1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("objectName"))));
    }

    @Test
    void shouldRejectMalformedJsonWithoutLeakingInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request."))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private AlertCase sampleAlert() {
        return new AlertCase(
                "alert-1",
                "txn-1",
                "cust-1",
                "corr-1",
                Instant.parse("2026-04-20T09:00:00Z"),
                Instant.parse("2026-04-20T09:00:10Z"),
                RiskLevel.HIGH,
                0.91d,
                AlertStatus.OPEN,
                "High-risk transaction flagged for review.",
                List.of("DEVICE_NOVELTY"),
                new Money(new BigDecimal("100.00"), "USD"),
                new MerchantInfo("m-1", "Merchant", "5411", "Groceries", "US", "ECOMMERCE", false, Map.of()),
                new DeviceInfo("d-1", "fp-1", "203.0.113.2", "Mozilla", "IOS", "SAFARI", false, false, false, Map.of()),
                new LocationInfo("US", "CA", "San Francisco", "94105", 37.7, -122.4, "America/Los_Angeles", false),
                new CustomerContext("cust-1", "acct-1", "STANDARD", "example.com", 500, true, true, "US", "USD", List.of("d-1"), Map.of()),
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                List.of(),
                null
        );
    }

    private SubmitAnalystDecisionRequest decisionRequest() {
        return new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc", "velocity"),
                Map.of()
        );
    }

    private AnalystCaseSummaryResponse sampleAssistantSummary() {
        AlertCase alert = sampleAlert();
        return new AnalystCaseSummaryResponse(
                alert.alertId(),
                alert.transactionId(),
                alert.customerId(),
                alert.correlationId(),
                new TransactionSummary(
                        alert.transactionId(),
                        alert.alertTimestamp(),
                        alert.transactionAmount(),
                        alert.merchantInfo().merchantId(),
                        alert.merchantInfo().merchantName(),
                        alert.merchantInfo().merchantCategory(),
                        alert.merchantInfo().channel(),
                        alert.locationInfo().countryCode(),
                        alert.fraudScore(),
                        alert.riskLevel()
                ),
                List.of(new FraudReasonSummary("DEVICE_NOVELTY", "New or unusual device", "Device differs from known behavior.", 0.4d, Map.of())),
                new CustomerRecentBehaviorSummary(alert.customerId(), "STANDARD", 500, 6, alert.transactionAmount(), 0.4d, 3, true, false, false, null),
                new RecommendedNextAction("STEP_UP_REVIEW", "Review identity and device signals", "Device signal requires review.", List.of("Inspect device novelty")),
                Map.of(),
                Instant.parse("2026-04-20T10:01:00Z")
        );
    }
}
