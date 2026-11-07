package com.frauddetection.alert.controller;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.domain.ScoringOccurrenceToken;
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
import com.frauddetection.alert.service.ScoredTransactionSearchResult;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.model.MerchantInfo;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final String EXPECTED_SCORING_OCCURRENCE_TOKEN = ScoringOccurrenceToken.from(
            ScoringOccurrenceOwnership.authoritative(
                    "event-txn-1",
                    Instant.parse("2026-06-18T10:00:01Z"),
                    "a".repeat(64)
            )
    );

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
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-1"), any()))
                .thenReturn(projectedReadModel("txn-1"));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.fraudScore").value(0.91d))
                .andExpect(jsonPath("$.scoringOccurrenceToken").value(EXPECTED_SCORING_OCCURRENCE_TOKEN))
                .andExpect(jsonPath("$.engineIntelligence.status").value("DEGRADED"))
                .andExpect(jsonPath("$.engineIntelligence.contractVersion").value(2))
                .andExpect(jsonPath("$.engineIntelligence.comparison.agreementStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.engineIntelligence.engines[0].engineId").value("rules.primary"))
                .andExpect(jsonPath("$.analystRecommendation.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.analystRecommendation.recommendation").value("RECOMMEND_REVIEW"))
                .andExpect(jsonPath("$.analystRecommendation.recommendationVersion").value("analyst-recommendation-v1"))
                .andExpect(jsonPath("$.analystRecommendation.generatedAt").value("2026-06-19T10:00:00Z"))
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
                "sourceEventFingerprint",
                "a".repeat(64),
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
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-old"), any()))
                .thenReturn(EngineIntelligenceReadModel.notProjected("txn-old"));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analystRecommendation.status").value("ABSENT"))
                .andExpect(jsonPath("$.analystRecommendation.recommendation").isEmpty())
                .andExpect(jsonPath("$.analystRecommendation.recommendationVersion").value("analyst-recommendation-v1"))
                .andExpect(jsonPath("$.analystRecommendation.generatedAt").isEmpty())
                .andExpect(jsonPath("$.analystRecommendation.confidence").value("UNKNOWN"))
                .andExpect(jsonPath("$.analystRecommendation.source").value("ENGINE_INTELLIGENCE_ABSENT"))
                .andExpect(jsonPath("$.analystRecommendation.reasonCodes").isArray())
                .andExpect(jsonPath("$.analystRecommendation.warnings").isArray())
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notPaymentAuthorization").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notAutomaticDecisioning").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notCaseAction").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notWorkflowAction").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notModelPromotion").value(true))
                .andExpect(jsonPath("$.analystRecommendation.nonDecisioning.notThresholdRecommendation").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .contains("\"analystRecommendation\"", "\"generatedAt\":null")
                .doesNotContain("1970-01-01T00:00:00Z");
    }

    @Test
    void detailEndpointReturnsAbsentEngineIntelligenceForOldTransaction() throws Exception {
        when(transactionMonitoringUseCase.getScoredTransaction("txn-old")).thenReturn(scoredTransaction("txn-old"));
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-old"), any()))
                .thenReturn(EngineIntelligenceReadModel.notProjected("txn-old"));

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
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-store-failure"), any()))
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
                .andExpect(jsonPath("$.content[0].analystRecommendation").doesNotExist())
                .andExpect(jsonPath("$.content[0].scoringOccurrenceToken").value(EXPECTED_SCORING_OCCURRENCE_TOKEN))
                .andExpect(jsonPath("$.totalElementsCapped").value(false));

        verify(engineIntelligenceReadService, org.mockito.Mockito.never()).read(any());
    }

    @Test
    void scoredListUsesTimestampAndUniqueTransactionIdSortKeys() throws Exception {
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transactions/scored"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(transactionMonitoringUseCase).listScoredTransactions(pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("scoredAt", Sort.Direction.DESC),
                        org.assertj.core.groups.Tuple.tuple("transactionId", Sort.Direction.DESC)
                );
    }

    @Test
    void filteredListReportsWhetherTheBoundedTotalProbeFoundMoreMatches() throws Exception {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 25);
        var capped = new ScoredTransactionSearchResult(
                new PageImpl<>(List.of(scoredTransaction("txn-capped")), pageable, 10_000), true
        );
        var exact = new ScoredTransactionSearchResult(
                new PageImpl<>(List.of(scoredTransaction("txn-exact")), pageable, 10_000), false
        );
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class), any()))
                .thenReturn(capped)
                .thenReturn(exact);

        mockMvc.perform(get("/api/v1/transactions/scored").queryParam("query", "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10_000))
                .andExpect(jsonPath("$.totalElementsCapped").value(true));
        mockMvc.perform(get("/api/v1/transactions/scored").queryParam("query", "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10_000))
                .andExpect(jsonPath("$.totalElementsCapped").value(false));
    }

    @Test
    void scoredListAndDetailOmitUnreviewedMerchantAttributes() throws Exception {
        MerchantInfo merchantInfo = new MerchantInfo(
                "merchant-1", "Market", "5411", "Grocery", "PL", "ECOMMERCE", true,
                Map.of("opaqueSentinel", "PRIVATE_MERCHANT_ATTRIBUTE_SENTINEL",
                        "nested", Map.of("rawDetail", "NESTED_MERCHANT_SENTINEL"))
        );
        ScoredTransaction transaction = scoredTransactionWithMerchant("txn-merchant", merchantInfo);
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transaction)));
        when(transactionMonitoringUseCase.getScoredTransaction("txn-merchant")).thenReturn(transaction);
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-merchant"), any()))
                .thenReturn(EngineIntelligenceReadModel.notProjected("txn-merchant"));

        String listJson = mockMvc.perform(get("/api/v1/transactions/scored"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].merchantInfo.merchantId").value("merchant-1"))
                .andExpect(jsonPath("$.content[0].merchantInfo.merchantName").value("Market"))
                .andExpect(jsonPath("$.content[0].merchantInfo.channel").value("ECOMMERCE"))
                .andReturn().getResponse().getContentAsString();
        String detailJson = mockMvc.perform(get("/api/v1/transactions/scored/txn-merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantInfo.merchantId").value("merchant-1"))
                .andExpect(jsonPath("$.merchantInfo.merchantCategoryCode").value("5411"))
                .andExpect(jsonPath("$.merchantInfo.acquiringCountryCode").value("PL"))
                .andReturn().getResponse().getContentAsString();

        for (String json : List.of(listJson, detailJson)) {
            assertThat(json).doesNotContain(
                    "attributes", "cardPresent", "PRIVATE_MERCHANT_ATTRIBUTE_SENTINEL", "NESTED_MERCHANT_SENTINEL"
            );
        }
    }

    @Test
    void scoredTransactionListAndDetailSerializeBigDecimalAsExactJsonNumbers() throws Exception {
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        scoredTransaction("txn-ordinary", new Money(new BigDecimal("1249.99"), "USD")),
                        scoredTransaction("txn-supported-maximum", new Money(new BigDecimal("999999999999.99"), "USD")),
                        scoredTransaction("txn-outside-ingest-bound", new Money(new BigDecimal("9007199254740993.01"), "USD"))
                )));
        when(transactionMonitoringUseCase.getScoredTransaction("txn-detail"))
                .thenReturn(scoredTransaction("txn-detail", new Money(new BigDecimal("9007199254740993.01"), "USD")));
        when(engineIntelligenceReadService.readForOccurrence(eq("txn-detail"), any()))
                .thenReturn(EngineIntelligenceReadModel.notProjected("txn-detail"));

        String listResponse = mockMvc.perform(get("/api/v1/transactions/scored"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String detailResponse = mockMvc.perform(get("/api/v1/transactions/scored/txn-detail"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(listResponse).contains(
                "\"transactionAmount\":{\"amount\":1249.99,\"currency\":\"USD\"}",
                "\"transactionAmount\":{\"amount\":999999999999.99,\"currency\":\"USD\"}",
                "\"transactionAmount\":{\"amount\":9007199254740993.01,\"currency\":\"USD\"}"
        );
        assertThat(detailResponse).contains(
                "\"transactionAmount\":{\"amount\":9007199254740993.01,\"currency\":\"USD\"}"
        );
    }

    private ScoredTransaction scoredTransaction(String transactionId) {
        return scoredTransaction(transactionId, null);
    }

    private ScoredTransaction scoredTransaction(String transactionId, Money transactionAmount) {
        return scoredTransaction(transactionId, transactionAmount, null);
    }

    private ScoredTransaction scoredTransaction(String transactionId, Money transactionAmount, MerchantInfo merchantInfo) {
        return new ScoredTransaction(
                transactionId,
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                transactionAmount,
                merchantInfo,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                analystRecommendation(),
                ScoringOccurrenceOwnership.authoritative(
                        "event-" + transactionId,
                        Instant.parse("2026-06-18T10:00:01Z"),
                        "a".repeat(64)
                ),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
    }

    private ScoredTransaction scoredTransactionWithMerchant(String transactionId, MerchantInfo merchantInfo) {
        return scoredTransaction(transactionId, null, merchantInfo);
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
                null,
                ScoringOccurrenceOwnership.authoritative(
                        "event-" + transactionId,
                        Instant.parse("2026-06-18T10:00:01Z"),
                        "a".repeat(64)
                ),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
    }

    private AnalystRecommendationResult analystRecommendation() {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                Instant.parse("2026-06-19T10:00:00Z"),
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
                EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-06-18T10:00:02Z"),
                new EngineIntelligenceComparisonReadModel(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(
                        new EngineIntelligenceEngineReadModel(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.CRITICAL,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineReadModel(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.TIMEOUT,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_TIMEOUT")
                        )
                ),
                List.of(),
                List.of()
        );
    }
}
