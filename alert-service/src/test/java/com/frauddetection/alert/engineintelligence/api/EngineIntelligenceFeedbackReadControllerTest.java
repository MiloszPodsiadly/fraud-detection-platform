package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
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

    @Test
    void returnsFeedbackForTransactionAndCreatesBoundedReadAudit() throws Exception {
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
        verifyNoMoreInteractions(sensitiveReadAuditService);
    }

    @Test
    void returnsEmptyListWhenNoFeedbackExists() throws Exception {
        when(service.read("txn-1", 25)).thenReturn(response("txn-1", 25, false));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.feedback").isArray())
                .andExpect(jsonPath("$.feedback").isEmpty())
                .andExpect(jsonPath("$.page.limit").value(25))
                .andExpect(jsonPath("$.page.hasMore").value(false));
    }

    @Test
    void missingTransactionReturnsNotFound() throws Exception {
        when(service.read("txn-missing", 25)).thenThrow(new EngineIntelligenceScoredTransactionNotFoundException());

        mockMvc.perform(get("/api/v1/transactions/scored/txn-missing/engine-intelligence/feedback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:SCORED_TRANSACTION_NOT_FOUND"));
    }

    @Test
    void invalidLimitReturnsBoundedBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid engine intelligence feedback request."))
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
    void repositoryFailureReturnsServiceUnavailableWithoutRawDetails() throws Exception {
        when(service.read("txn-1", 25)).thenThrow(new EngineIntelligenceFeedbackReadUnavailableException());

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details[0]").value("reason:ENGINE_INTELLIGENCE_FEEDBACK_STORE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("payload", "token", "secret", "stacktrace", "endpoint");
    }

    @Test
    void auditFailureReturnsBoundedServiceUnavailableWithoutRawAuditError() throws Exception {
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
