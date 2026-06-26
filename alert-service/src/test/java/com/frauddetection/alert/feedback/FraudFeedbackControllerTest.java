package com.frauddetection.alert.feedback;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = FraudFeedbackController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class FraudFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudFeedbackService service;

    @Test
    void createsFeedbackForScoredTransaction() throws Exception {
        when(service.create(org.mockito.Mockito.eq("txn-1"), org.mockito.Mockito.any()))
                .thenReturn(response("feedback-1", FraudFeedbackLabel.CONFIRMED_FRAUD));

        String body = mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "analystDecision": "MARKED_FRAUD",
                                  "feedbackLabel": "CONFIRMED_FRAUD",
                                  "decisionReasonCodes": ["CUSTOMER_CONFIRMED_FRAUD"],
                                  "notes": "Customer confirmed fraud"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").value("feedback-1"))
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.feedbackLabel").value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.labelSource").value("ANALYST_REVIEW"))
                .andExpect(jsonPath("$.feedbackStatus").value("RECORDED"))
                .andExpect(jsonPath("$.notesPresent").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(
                "notes\"",
                "Customer confirmed fraud",
                "groundTruth",
                "trainingLabel",
                "finalDecision",
                "paymentDecision",
                "paymentAuthorization",
                "rawMlRequest",
                "rawMlResponse",
                "rawFeatureVector",
                "rawEvidence"
        );
    }

    @Test
    void readsExistingFeedback() throws Exception {
        when(service.get("txn-1")).thenReturn(response("feedback-1", FraudFeedbackLabel.CONFIRMED_LEGITIMATE));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackId").value("feedback-1"))
                .andExpect(jsonPath("$.feedbackLabel").value("CONFIRMED_LEGITIMATE"));
    }

    @Test
    void duplicateFeedbackReturns409() throws Exception {
        when(service.create(org.mockito.Mockito.eq("txn-1"), org.mockito.Mockito.any()))
                .thenThrow(new ResponseStatusException(CONFLICT, "FRAUD_FEEDBACK_ALREADY_RECORDED"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_ALREADY_RECORDED"));
    }

    @Test
    void invalidFeedbackReturns400() throws Exception {
        when(service.create(org.mockito.Mockito.eq("txn-1"), org.mockito.Mockito.any()))
                .thenThrow(new ResponseStatusException(BAD_REQUEST, "FRAUD_FEEDBACK_NOTES_UNSAFE"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_NOTES_UNSAFE"));
    }

    @Test
    void noFeedbackReturns404() throws Exception {
        when(service.get("txn-1")).thenThrow(new ResponseStatusException(NOT_FOUND, "FRAUD_FEEDBACK_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/feedback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("FRAUD_FEEDBACK_NOT_FOUND"));
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

    private FraudFeedbackResponse response(String feedbackId, FraudFeedbackLabel feedbackLabel) {
        return new FraudFeedbackResponse(
                feedbackId,
                "txn-1",
                "customer-1",
                "corr-1",
                feedbackLabel == FraudFeedbackLabel.CONFIRMED_LEGITIMATE
                        ? AnalystDecision.MARKED_LEGITIMATE
                        : AnalystDecision.MARKED_FRAUD,
                feedbackLabel,
                FeedbackLabelSource.ANALYST_REVIEW,
                FraudFeedbackStatus.RECORDED,
                Instant.parse("2026-06-25T10:15:30Z"),
                "analyst-1",
                List.of("CUSTOMER_CONFIRMED_FRAUD"),
                false,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                Instant.parse("2026-06-25T09:00:01Z"),
                Instant.parse("2026-06-25T09:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()
        );
    }
}
