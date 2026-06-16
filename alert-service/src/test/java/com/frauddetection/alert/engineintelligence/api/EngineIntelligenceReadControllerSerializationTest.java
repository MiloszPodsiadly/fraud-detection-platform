package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceWarningProjection;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
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
import java.util.Optional;

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
@Import({
        AlertServiceExceptionHandler.class,
        EngineIntelligenceReadService.class,
        EngineIntelligenceReadModelMapper.class
})
class EngineIntelligenceReadControllerSerializationTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-06-02T13:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScoredTransactionRepository scoredTransactionRepository;

    @MockitoBean
    private EngineIntelligenceProjectionRepository projectionRepository;

    @Test
    void apiReturnsFullBoundedEngineIntelligenceWithoutRawInternalOrDecisioningFields() throws Exception {
        when(scoredTransactionRepository.existsById("txn-full")).thenReturn(true);
        when(projectionRepository.findById("txn-full")).thenReturn(Optional.of(fullProjection()));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-full/engine-intelligence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.transactionId").value("txn-full"))
                .andExpect(jsonPath("$.contractVersion").value(1))
                .andExpect(jsonPath("$.comparison.agreementStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.comparison.riskMismatchStatus").value("NOT_COMPARABLE"))
                .andExpect(jsonPath("$.comparison.scoreDeltaBucket").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.engines.length()").value(2))
                .andExpect(jsonPath("$.diagnosticSignals.length()").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(2))
                .andExpect(jsonPath("$.engines[1].status").value("TIMEOUT"))
                .andExpect(jsonPath("$.engines[1].riskLevel").doesNotExist())
                .andExpect(jsonPath("$.diagnosticSignals[1].signalCategory").value("OPERATIONAL_SIGNAL"))
                .andExpect(jsonPath("$.diagnosticSignals[1].riskLevel").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(
                "rawEvidence", "rawContribution", "featureSnapshot", "featureVector", "rawPayload", "payload",
                "endpoint", "token", "secret", "stacktrace", "exceptionMessage", "internalAggregation",
                "FraudEngineAggregationResult", "NormalizedFraudEngineResult", "ScoringContext", "rawMlResponse",
                "_id", "createdAt", "updatedAt", "EngineIntelligenceProjection", "finalDecision",
                "recommendedAction", "approve", "decline", "block", "winningEngine", "platformRiskScore",
                "paymentAuthorization"
        );
    }

    @Test
    void corruptedProjectionFailureReturnsStableUnavailableResponseWithoutRawValue() throws Exception {
        when(scoredTransactionRepository.existsById("txn-corrupted")).thenReturn(true);
        when(projectionRepository.findById("txn-corrupted")).thenReturn(Optional.of(corruptedProjection()));

        String response = mockMvc.perform(get("/api/v1/transactions/scored/txn-corrupted/engine-intelligence"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Engine intelligence projection is temporarily unavailable."))
                .andExpect(jsonPath("$.details[0]")
                        .value("reason:ENGINE_INTELLIGENCE_PROJECTION_STORE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("rawEvidence", "txn-corrupted");
    }

    private EngineIntelligenceProjection fullProjection() {
        return new EngineIntelligenceProjection(
                "txn-full",
                1,
                GENERATED_AT,
                EngineIntelligenceAgreementStatus.PARTIAL,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                List.of(
                        new EngineIntelligenceEngineProjection(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineProjection(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.TIMEOUT,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_TIMEOUT")
                        )
                ),
                List.of(
                        new EngineIntelligenceDiagnosticSignalProjection(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                "HIGH_VELOCITY"
                        ),
                        new EngineIntelligenceDiagnosticSignalProjection(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.TIMEOUT,
                                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                "ML_MODEL_TIMEOUT"
                        )
                ),
                List.of(
                        new EngineIntelligenceWarningProjection(EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED, 1),
                        new EngineIntelligenceWarningProjection(EngineIntelligenceWarningCode.REASON_CODE_LIMIT_APPLIED, 1)
                ),
                GENERATED_AT,
                GENERATED_AT
        );
    }

    private EngineIntelligenceProjection corruptedProjection() {
        return new EngineIntelligenceProjection(
                "txn-corrupted",
                1,
                GENERATED_AT,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                List.of(new EngineIntelligenceEngineProjection(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("rawEvidence")
                )),
                List.of(),
                List.of(),
                GENERATED_AT,
                GENERATED_AT
        );
    }
}
