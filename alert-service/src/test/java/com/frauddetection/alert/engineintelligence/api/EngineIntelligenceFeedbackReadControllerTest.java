package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = EngineIntelligenceFeedbackReadController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AlertServiceExceptionHandler.class, EngineIntelligenceFeedbackReadQueryPolicy.class})
class EngineIntelligenceFeedbackReadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineIntelligenceFeedbackReadService service;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private AlertServiceMetrics metrics;

    @Test
    void feedbackReadAuditsExactlyOnce() throws Exception {
        when(service.read("txn-1", 25)).thenReturn(response("txn-1", 25, false, entry("feedback-1")));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.feedback[0].feedbackId").value("feedback-1"))
                .andExpect(jsonPath("$.feedback[0].selectedReasonCodes[0]").value("HIGH_VELOCITY"))
                .andExpect(jsonPath("$.page.limit").value(25))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.feedback[0].submittedBy").doesNotExist())
                .andExpect(jsonPath("$.feedback[0].idempotencyKeyHash").doesNotExist())
                .andExpect(jsonPath("$.feedback[0].requestPayloadHash").doesNotExist())
                .andExpect(jsonPath("$.feedback[0].correlationId").doesNotExist())
                .andExpect(jsonPath("$.feedback[0].createdAt").doesNotExist());

        ArgumentCaptor<HttpServletRequest> request = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(sensitiveReadAuditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_FEEDBACK_READ),
                eq(ReadAccessResourceType.ENGINE_INTELLIGENCE_FEEDBACK),
                eq("txn-1"),
                eq(1),
                request.capture()
        );
        verify(metrics).recordEngineIntelligenceFeedbackReadSuccess();
        verifyNoMoreInteractions(sensitiveReadAuditService);
    }

    @Test
    void emptyFeedbackReadAuditsExactlyOnceWithZeroResultCount() throws Exception {
        when(service.read("txn-1", 25)).thenReturn(response("txn-1", 25, false));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.feedback").isArray())
                .andExpect(jsonPath("$.feedback").isEmpty())
                .andExpect(jsonPath("$.page.limit").value(25))
                .andExpect(jsonPath("$.page.hasMore").value(false));

        verify(sensitiveReadAuditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_FEEDBACK_READ),
                eq(ReadAccessResourceType.ENGINE_INTELLIGENCE_FEEDBACK),
                eq("txn-1"),
                eq(0),
                org.mockito.ArgumentMatchers.any(HttpServletRequest.class)
        );
        verify(metrics).recordEngineIntelligenceFeedbackReadEmpty();
        verifyNoMoreInteractions(sensitiveReadAuditService);
    }

    @Test
    void missingTransactionDoesNotAuditSuccess() throws Exception {
        when(service.read("txn-missing", 25)).thenThrow(new EngineIntelligenceScoredTransactionNotFoundException());

        mockMvc.perform(get("/api/v1/transactions/scored/txn-missing/engine-intelligence/feedback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:SCORED_TRANSACTION_NOT_FOUND"));

        verifyNoInteractions(sensitiveReadAuditService);
    }

    @Test
    void defaultLimitIsTwentyFive() throws Exception {
        when(service.read("txn-1", 25)).thenReturn(response("txn-1", 25, false));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.limit").value(25));

        verify(service).read("txn-1", 25);
    }

    @Test
    void limitAboveMaximumReturnsBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid engine intelligence feedback request."))
                .andExpect(jsonPath("$.details[0]").value("limit: must be between 1 and 50"));
        verify(metrics).recordEngineIntelligenceFeedbackReadValidationFailure();
    }

    @Test
    void zeroLimitReturnsBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("limit: must be between 1 and 50"));
    }

    @Test
    void negativeLimitReturnsBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("limit: must be between 1 and 50"));
    }

    @Test
    void nonIntegerLimitReturnsBoundedBadRequestWithoutRawServletDetails() throws Exception {
        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("limit: must be between 1 and 50"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("NumberFormatException")
                .doesNotContain("MethodArgumentTypeMismatch")
                .doesNotContain("stacktrace");
    }

    @Test
    void duplicateLimitParamsReturnBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "25", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("query: invalid parameters"));
    }

    @Test
    void unknownQueryParamReturnsBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "25")
                        .param("cursor", "token-secret-stacktrace"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("query: invalid parameters"));
    }

    @Test
    void feedbackRepositoryFailureDoesNotAuditSuccess() throws Exception {
        when(service.read("txn-1", 25)).thenThrow(new EngineIntelligenceFeedbackReadUnavailableException());

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details[0]").value("reason:ENGINE_INTELLIGENCE_FEEDBACK_STORE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("payload", "token", "secret", "stacktrace", "endpoint");
        verifyNoInteractions(sensitiveReadAuditService);
    }

    @Test
    void feedbackReadAuditFailureReturnsBoundedServiceUnavailable() throws Exception {
        when(service.read("txn-1", 25)).thenReturn(response("txn-1", 25, false, entry("feedback-1")));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sensitive read audit unavailable."))
                .when(sensitiveReadAuditService)
                .audit(
                        eq(ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_FEEDBACK_READ),
                        eq(ReadAccessResourceType.ENGINE_INTELLIGENCE_FEEDBACK),
                        eq("txn-1"),
                        eq(1),
                        org.mockito.ArgumentMatchers.any(HttpServletRequest.class)
                );

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Sensitive read audit unavailable."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("audit unavailable token secret stacktrace");
        verify(metrics).recordEngineIntelligenceFeedbackReadAuditFailure();
        verify(metrics).recordEngineIntelligenceFeedbackReadUnavailable(EngineIntelligenceFeedbackReadMetricReason.AUDIT_FAILURE);
    }

    @Test
    void unknownSiblingRouteDoesNotAuditFeedbackRead() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback/not-real"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service, sensitiveReadAuditService);
    }

    private EngineIntelligenceFeedbackReadModel response(
            String transactionId,
            int limit,
            boolean hasMore,
            EngineIntelligenceFeedbackEntryReadModel... feedback
    ) {
        return new EngineIntelligenceFeedbackReadModel(
                transactionId,
                List.of(feedback),
                new EngineIntelligenceFeedbackPage(limit, hasMore)
        );
    }

    private EngineIntelligenceFeedbackEntryReadModel entry(String feedbackId) {
        return new EngineIntelligenceFeedbackEntryReadModel(
                feedbackId,
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                Instant.parse("2026-06-04T10:15:30Z")
        );
    }
}
