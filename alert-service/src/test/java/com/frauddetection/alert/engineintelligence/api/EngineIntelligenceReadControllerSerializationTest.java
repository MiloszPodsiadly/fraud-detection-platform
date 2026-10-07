package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceWarningProjection;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
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
    private static final String SOURCE_EVENT_FINGERPRINT = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScoredTransactionRepository scoredTransactionRepository;

    @MockitoBean
    private EngineIntelligenceProjectionRepository projectionRepository;

    @Test
    void apiReturnsFullBoundedEngineIntelligenceWithoutRawInternalOrDecisioningFields() throws Exception {
        when(scoredTransactionRepository.findById("txn-full"))
                .thenReturn(Optional.of(authoritativeOccurrence("txn-full", "event-full")));
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
        when(scoredTransactionRepository.findById("txn-corrupted"))
                .thenReturn(Optional.of(authoritativeOccurrence("txn-corrupted", "event-corrupted")));
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
                new ScoringOccurrenceOwnership("event-full", GENERATED_AT, SOURCE_EVENT_FINGERPRINT),
                1,
                GENERATED_AT,
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of("rules.primary", "ml.python.primary"),
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
                new ScoringOccurrenceOwnership("event-corrupted", GENERATED_AT, SOURCE_EVENT_FINGERPRINT),
                1,
                GENERATED_AT,
                null,
                null,
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

    private ScoredTransactionDocument authoritativeOccurrence(String transactionId, String sourceEventId) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        document.setSourceEventId(sourceEventId);
        document.setSourceEventCreatedAt(GENERATED_AT.toString());
        document.setSourceEventCreatedAtEpochSecond(GENERATED_AT.getEpochSecond());
        document.setSourceEventCreatedAtNano(GENERATED_AT.getNano());
        document.setSourceEventFingerprint(SOURCE_EVENT_FINGERPRINT);
        return document;
    }
}
