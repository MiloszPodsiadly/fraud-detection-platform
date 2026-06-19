package com.frauddetection.alert.controller;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceEngineReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ScoredTransactionNotFoundException;
import com.frauddetection.alert.service.ScoredTransactionReadValidationException;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationConfidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationNonDecisioning;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.common.events.recommendation.AnalystRecommendationSource;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ScoredTransactionController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        AlertResponseMapper.class,
        ScoredTransactionResponseMapper.class,
        EngineIntelligenceResponseMapper.class,
        ScoredTransactionSearchPolicy.class,
        AlertServiceExceptionHandler.class
})
class ScoredTransactionControllerDetailTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @MockitoBean
    private EngineIntelligenceReadService engineIntelligenceReadService;

    @MockitoBean
    private AlertServiceMetrics metrics;

    @Test
    void detailEndpointReturnsScoredTransactionWithEngineIntelligence() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-1")).thenReturn(scoredTransaction("txn-1"));
        when(engineIntelligenceReadService.read("txn-1")).thenReturn(projectedReadModel("txn-1"));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.fraudScore").value(0.91d))
                .andExpect(jsonPath("$.engineIntelligence.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.engineIntelligence.contractVersion").value(1))
                .andExpect(jsonPath("$.engineIntelligence.comparison.agreementStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.engineIntelligence.engines[0].engineId").value("rules.primary"))
                .andExpect(jsonPath("$.analystRecommendation.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.analystRecommendation.recommendation").value("RECOMMEND_REVIEW"))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notPaymentAuthorization").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(
                "FraudEngineResult",
                "rawFeatureVector",
                "rawMlRequest",
                "rawMlResponse",
                "rawEvidence",
                "groundTruth",
                "trainingLabel",
                "finalDecision",
                "paymentDecision",
                "paymentAuthorization"
        );
    }

    @Test
    void detailEndpointReturnsAbsentAnalystRecommendationForOldTransactionProjection() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-old"))
                .thenReturn(scoredTransactionWithoutRecommendation("txn-old"));
        when(engineIntelligenceReadService.read("txn-old")).thenReturn(EngineIntelligenceReadModel.notProjected("txn-old"));

        mockMvc.perform(get("/api/v1/transactions/scored/txn-old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analystRecommendation.status").value("ABSENT"))
                .andExpect(jsonPath("$.analystRecommendation.recommendation").isEmpty())
                .andExpect(jsonPath("$.analystRecommendation.confidence").value("UNKNOWN"))
                .andExpect(jsonPath("$.analystRecommendation.source").value("ENGINE_INTELLIGENCE_ABSENT"))
                .andExpect(jsonPath("$.analystRecommendation.reasonCodes").isArray())
                .andExpect(jsonPath("$.analystRecommendation.warnings").isArray())
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notPaymentAuthorization").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notAutomaticDecisioning").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notCaseAction").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notWorkflowAction").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notModelPromotion").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notThresholdRecommendation").value(true));
    }

    @Test
    void detailEndpointReturnsAbsentEngineIntelligenceForOldTransaction() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-old")).thenReturn(scoredTransaction("txn-old"));
        when(engineIntelligenceReadService.read("txn-old")).thenReturn(EngineIntelligenceReadModel.notProjected("txn-old"));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-old"))
                .andExpect(jsonPath("$.engineIntelligence.status").value("ABSENT"))
                .andExpect(jsonPath("$.engineIntelligence.contractVersion").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.generatedAt").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.comparison").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.engines").isArray())
                .andExpect(jsonPath("$.engineIntelligence.diagnosticSignals").isArray())
                .andExpect(jsonPath("$.engineIntelligence.warnings").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains(
                "\"engineIntelligence\"",
                "\"status\":\"ABSENT\"",
                "\"contractVersion\":null",
                "\"generatedAt\":null",
                "\"comparison\":null",
                "\"engines\":[]",
                "\"diagnosticSignals\":[]",
                "\"warnings\":[]"
        );
    }

    @Test
    void detailEndpointKeepsTransactionReadableWhenEngineIntelligenceProjectionIsUnavailable() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-store-failure")).thenReturn(scoredTransaction("txn-store-failure"));
        when(engineIntelligenceReadService.read("txn-store-failure"))
                .thenThrow(new EngineIntelligenceProjectionReadUnavailableException());

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-store-failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-store-failure"))
                .andExpect(jsonPath("$.engineIntelligence.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.engineIntelligence.contractVersion").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.generatedAt").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.comparison").isEmpty())
                .andExpect(jsonPath("$.engineIntelligence.engines").isArray())
                .andExpect(jsonPath("$.engineIntelligence.diagnosticSignals").isArray())
                .andExpect(jsonPath("$.engineIntelligence.warnings").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains(
                "\"engineIntelligence\"",
                "\"status\":\"UNAVAILABLE\"",
                "\"contractVersion\":null",
                "\"generatedAt\":null",
                "\"comparison\":null",
                "\"engines\":[]",
                "\"diagnosticSignals\":[]",
                "\"warnings\":[]"
        );
    }

    @Test
    void invalidTransactionIdReturns400WithoutEngineIntelligenceLookup() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn$secret"))
                .thenThrow(new ScoredTransactionReadValidationException("INVALID_TRANSACTION_ID"));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn$secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_TRANSACTION_ID"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("txn$secret");
        verifyNoInteractions(engineIntelligenceReadService);
    }

    @Test
    void unknownTransactionIdReturns404WithoutEngineIntelligenceLookup() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-missing"))
                .thenThrow(new ScoredTransactionNotFoundException());

        mockMvc.perform(get("/api/v1/transactions/scored/txn-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:SCORED_TRANSACTION_NOT_FOUND"));

        verifyNoInteractions(engineIntelligenceReadService);
    }

    @Test
    void listEndpointRemainsLightweightAndDoesNotReadEngineIntelligence() throws Exception {
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(scoredTransaction("txn-1"))));

        mockMvc.perform(get("/api/v1/transactions/scored"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value("txn-1"))
                .andExpect(jsonPath("$.content[0].engineIntelligence").doesNotExist())
                .andExpect(jsonPath("$.content[0].analystRecommendation").doesNotExist());

        verify(engineIntelligenceReadService, org.mockito.Mockito.never()).read(any());
    }

    private ScoredTransaction scoredTransaction(String transactionId) {
        return new ScoredTransaction(
                transactionId,
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                analystRecommendation()
        );
    }

    private ScoredTransaction scoredTransactionWithoutRecommendation(String transactionId) {
        return new ScoredTransaction(
                transactionId,
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                null
        );
    }

    private AnalystRecommendationResult analystRecommendation() {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.MEDIUM,
                AnalystRecommendationSource.RULES_RISK,
                List.of("RULES_CRITICAL_RISK"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    private EngineIntelligenceReadModel projectedReadModel(String transactionId) {
        return EngineIntelligenceReadModel.projected(
                transactionId,
                1,
                Instant.parse("2026-06-18T10:00:02Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(new EngineIntelligenceEngineReadModel(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.CRITICAL,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("HIGH_VELOCITY")
                )),
                List.of(),
                List.of()
        );
    }
}
