package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = EngineIntelligenceReadController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class EngineIntelligenceReadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineIntelligenceReadService service;

    @Test
    void apiReturnsBoundedEngineIntelligenceWhenProjectionExists() throws Exception {
        when(service.read("txn-1")).thenReturn(EngineIntelligenceReadModel.projected(
                "txn-1",
                1,
                Instant.parse("2026-06-02T13:00:00Z"),
                new EngineIntelligenceComparisonReadModel(null, null, null),
                List.of(),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.engineCount").value(0))
                .andExpect(jsonPath("$.diagnosticSignalCount").value(0))
                .andExpect(jsonPath("$.warningCount").value(0));
    }

    @Test
    void apiReturnsAvailableFalseWhenProjectionMissingAndOldTransactionStillWorks() throws Exception {
        when(service.read("txn-old")).thenReturn(EngineIntelligenceReadModel.notProjected("txn-old"));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-old/engine-intelligence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-old"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_PROJECTED"))
                .andExpect(jsonPath("$.contractVersion").doesNotExist());
    }

    @Test
    void transactionNotFoundUsesControlledNotFoundBehavior() throws Exception {
        when(service.read("txn-missing")).thenThrow(new EngineIntelligenceScoredTransactionNotFoundException());

        mockMvc.perform(get("/api/v1/transactions/scored/txn-missing/engine-intelligence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Scored transaction not found."))
                .andExpect(jsonPath("$.details[0]").value("reason:SCORED_TRANSACTION_NOT_FOUND"));
    }
}
