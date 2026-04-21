package com.frauddetection.alert.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({AlertResponseMapper.class, AlertServiceExceptionHandler.class})
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AlertManagementUseCase alertManagementUseCase;

    @Test
    void shouldListAlerts() throws Exception {
        when(alertManagementUseCase.listAlerts()).thenReturn(List.of(sampleAlert()));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertId").value("alert-1"));
    }

    @Test
    void shouldSubmitAnalystDecision() throws Exception {
        when(alertManagementUseCase.submitDecision(org.mockito.ArgumentMatchers.eq("alert-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SubmitAnalystDecisionResponse("alert-1", AnalystDecision.CONFIRMED_FRAUD, AlertStatus.RESOLVED, "event-1", Instant.parse("2026-04-20T10:00:00Z")));

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("RESOLVED"));
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
}
