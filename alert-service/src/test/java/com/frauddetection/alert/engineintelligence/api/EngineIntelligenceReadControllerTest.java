package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @MockitoBean
    private EngineIntelligenceReadService service;

    @Test
    void apiReturnsBoundedEngineIntelligenceWhenProjectionExists() throws Exception {
        when(service.read("txn-1")).thenReturn(EngineIntelligenceReadModel.projected(
                "txn-1",
                1,
                Instant.parse("2026-06-02T13:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE),
                List.of(),
                List.of(),
                List.of()
        ));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1/engine-intelligence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.comparison.agreementStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.comparison.riskMismatchStatus").value("NOT_COMPARABLE"))
                .andExpect(jsonPath("$.comparison.scoreDeltaBucket").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.engineCount").value(0))
                .andExpect(jsonPath("$.diagnosticSignalCount").value(0))
                .andExpect(jsonPath("$.warningCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(
                "_id",
                "rawEvidence",
                "rawContribution",
                "featureSnapshot",
                "featureVector",
                "rawPayload",
                "payload",
                "endpoint",
                "token",
                "secret",
                "stacktrace",
                "exceptionMessage",
                "internalAggregation",
                "finalDecision",
                "recommendedAction",
                "approve",
                "decline",
                "block",
                "winningEngine",
                "platformRiskScore",
                "paymentAuthorization");
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

    @Test
    void controllerMapsProjectionStoreUnavailableTo503WithoutRawRepositoryException() throws Exception {
        when(service.read("txn-store-failure"))
                .thenThrow(new EngineIntelligenceProjectionReadUnavailableException());

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-store-failure/engine-intelligence"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Engine intelligence projection is temporarily unavailable."))
                .andExpect(jsonPath("$.details[0]")
                        .value("reason:ENGINE_INTELLIGENCE_PROJECTION_STORE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("mongodb", "repository", "endpoint", "token", "secret", "stacktrace");
    }
}
