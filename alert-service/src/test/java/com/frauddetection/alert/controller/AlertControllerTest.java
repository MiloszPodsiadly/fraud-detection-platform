package com.frauddetection.alert.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
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
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
@Import({AlertResponseMapper.class, AlertServiceExceptionHandler.class})
@Tag("production-readiness")
@Tag("integration")
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @Test
    void shouldListAlerts() throws Exception {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sampleAlert())));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].alertId").value("alert-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
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
                        SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
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
                .andExpect(jsonPath("$.operation_status").value("COMMITTED_EVIDENCE_PENDING"));
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
    void apiDoesNotExposeUpdatedResourceForRecoveryRequiredLegacy() throws Exception {
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
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("COMMITTED_EVIDENCE_PENDING")))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("COMMITTED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void apiDoesNotExposeUpdatedResourceForFinalizeRecoveryRequired() throws Exception {
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_status").value("FINALIZE_RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.decisionEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_PENDING_EXTERNAL")))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("FINALIZED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void budgetExceededRecovery_mustNotReturnCommittedSuccess() throws Exception {
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
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("COMMITTED_EVIDENCE_PENDING")));
    }

    @Test
    void staleCheckpointFailure_mustBeExplicitAndNotSuccessful() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("idem-stale")))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        null,
                        null,
                        null,
                        null,
                        SubmitDecisionOperationStatus.COMMIT_UNKNOWN
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-stale")
                        .content(objectMapper.writeValueAsString(decisionRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation_status").value("COMMIT_UNKNOWN"))
                .andExpect(jsonPath("$.resultingStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("COMMITTED_EVIDENCE_CONFIRMED")));
    }

    @Test
    void longRunningProcessing_mustBeObservableNotSuccessful() throws Exception {
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
                .andExpect(jsonPath("$.operation_status").value(org.hamcrest.Matchers.not("COMMITTED_EVIDENCE_PENDING")));
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
                .andExpect(jsonPath("$.details[0]").value("reason:REJECTED_BEFORE_MUTATION"))
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
                new CustomerRecentBehaviorSummary(alert.customerId(), "STANDARD", 500, 6, alert.transactionAmount(), 0.4d, 3, true, false, false, null, Map.of()),
                new RecommendedNextAction("STEP_UP_REVIEW", "Review identity and device signals", "Device signal requires review.", List.of("Inspect device novelty")),
                Map.of(),
                Instant.parse("2026-04-20T10:01:00Z")
        );
    }
}
