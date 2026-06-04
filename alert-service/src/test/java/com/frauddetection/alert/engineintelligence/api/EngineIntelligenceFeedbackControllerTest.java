package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.idempotency.SharedInvalidIdempotencyKeyException;
import com.frauddetection.alert.idempotency.SharedMissingIdempotencyKeyException;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = EngineIntelligenceFeedbackController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class EngineIntelligenceFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineIntelligenceFeedbackService service;

    @Test
    void validFeedbackRequestReturnsCreated() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("feedback-key-1")))
                .thenReturn(response("CREATED"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "feedback-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").value("feedback-1"))
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.submittedBy").doesNotExist())
                .andExpect(jsonPath("$.correlationId").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.operationStatus").value("CREATED"));
    }

    @Test
    void duplicateIdempotencyReturnsOkExisting() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("feedback-key-1")))
                .thenReturn(response("EXISTING"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "feedback-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationStatus").value("EXISTING"));
    }

    @Test
    void invalidFeedbackRequestReturnsBoundedBadRequest() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("feedback-key-1")))
                .thenThrow(new InvalidEngineIntelligenceFeedbackRequestException(List.of("feedbackType: invalid value")));

        String response = mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "feedback-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid engine intelligence feedback request."))
                .andExpect(jsonPath("$.details[0]").value("feedbackType: invalid value"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("payload", "token", "stacktrace", "endpoint");
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadReturnsConflict() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("feedback-key-1")))
                .thenThrow(new ConflictingIdempotencyKeyException());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "feedback-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"));
    }

    @Test
    void missingIdempotencyKeyReturnsBoundedBadRequest() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq(null)))
                .thenThrow(new SharedMissingIdempotencyKeyException());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void blankIdempotencyKeyReturnsBoundedBadRequest() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("   ")))
                .thenThrow(new SharedMissingIdempotencyKeyException());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void invalidIdempotencyKeyReturnsBoundedBadRequest() throws Exception {
        when(service.submit(eq("txn-1"), any(EngineIntelligenceFeedbackRequest.class), eq("bad-key-token/secret")))
                .thenThrow(new SharedInvalidIdempotencyKeyException());

        String response = mockMvc.perform(post("/api/v1/transactions/scored/txn-1/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "bad-key-token/secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_INVALID"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("bad-key-token", "secret");
    }

    @Test
    void missingTransactionReturnsNotFound() throws Exception {
        when(service.submit(eq("txn-missing"), any(EngineIntelligenceFeedbackRequest.class), eq("feedback-key-1")))
                .thenThrow(new EngineIntelligenceScoredTransactionNotFoundException());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-missing/engine-intelligence/feedback")
                        .header("X-Idempotency-Key", "feedback-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:SCORED_TRANSACTION_NOT_FOUND"));
    }

    private String request() {
        return """
                {
                  "feedbackType": "ENGINE_INTELLIGENCE_USEFULNESS",
                  "usefulness": "HELPFUL",
                  "accuracyAssessment": "SIGNALS_LOOK_CORRECT",
                  "engineIntelligenceAvailable": true,
                  "selectedReasonCodes": ["HIGH_VELOCITY"]
                }
                """;
    }

    private EngineIntelligenceFeedbackResponse response(String operationStatus) {
        return new EngineIntelligenceFeedbackResponse(
                "feedback-1",
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                Instant.parse("2026-06-03T10:15:30Z"),
                operationStatus
        );
    }
}
