package com.frauddetection.alert.feedback;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.config.SecurityDeniedAccessTelemetrySliceTestConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        AlertServiceExceptionHandler.class
})
class FraudFeedbackSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudFeedbackService service;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @Test
    void rejectsAnonymousFeedbackAccess() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/feedback"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
    }

    @Test
    void transactionReadAuthorityDoesNotAllowFeedbackWriteOrRead() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
    }

    @Test
    void allowsDedicatedFeedbackAuthorities() throws Exception {
        when(service.get("txn-1")).thenReturn(null);
        when(service.create(eq("txn-1"), any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_READ)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/transactions/scored/txn-1/feedback")
                        .with(userWith(AnalystAuthority.FRAUD_FEEDBACK_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated());
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
}
