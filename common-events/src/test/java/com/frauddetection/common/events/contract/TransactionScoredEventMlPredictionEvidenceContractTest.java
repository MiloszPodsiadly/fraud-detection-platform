package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEventMlPredictionEvidenceContractTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-03T10:15:30.123456Z");
    private static final String ARTIFACT_SHA256 = "a".repeat(64);
    private static final MlModelIdentity MODEL_IDENTITY = new MlModelIdentity(
            "python-logistic-fraud-model",
            "2026-05-30.v1",
            "2026-05-30.feature-contract.v1"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final JacksonKafkaDeserializer<TransactionScoredEvent> kafkaDeserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void exactMlPredictionEvidenceRoundTripsWithAssociatedPublicEngineSummary() throws Exception {
        ObjectNode json = eventJson();
        json.remove("mlPredictionEvidenceOmissionReason");
        json.set("engineIntelligence", objectMapper.valueToTree(summary()));
        json.set("mlPredictionEvidence", objectMapper.valueToTree(evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY)));

        TransactionScoredEvent event = objectMapper.readValue(json.toString(), TransactionScoredEvent.class);
        TransactionScoredEvent roundTrip = objectMapper.readValue(
                objectMapper.writeValueAsString(event),
                TransactionScoredEvent.class
        );

        assertThat(roundTrip.mlPredictionEvidence()).isEqualTo(evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY));
        assertThat(roundTrip.engineIntelligence()).isEqualTo(summary());
        assertThat(roundTrip.mlPredictionEvidence().mlScore()).isEqualTo(0.8123d);
    }

    @Test
    void currentEventWithoutPredictionEvidenceOutcomeFailsClosed() throws Exception {
        ObjectNode json = eventJson();
        json.remove("mlPredictionEvidenceOmissionReason");

        assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
    }

    @Test
    void missingContractVersionDoesNotImplyHistoricalProvenance() throws Exception {
        ObjectNode json = eventJson();
        json.remove("eventContractVersion");

        assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("TRANSACTION_SCORED_EVENT_CONTRACT_VERSION_REQUIRED");
    }

    @Test
    void retiredAndFutureContractVersionsFailClosed() throws Exception {
        ObjectNode retired = eventJson();
        retired.put("eventContractVersion", TransactionScoredEvent.CURRENT_CONTRACT_VERSION - 1);
        ObjectNode future = eventJson();
        future.put("eventContractVersion", TransactionScoredEvent.CURRENT_CONTRACT_VERSION + 1);

        assertThatThrownBy(() -> objectMapper.readValue(retired.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("TRANSACTION_SCORED_EVENT_CONTRACT_VERSION_UNSUPPORTED");
        assertThatThrownBy(() -> objectMapper.readValue(future.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("TRANSACTION_SCORED_EVENT_CONTRACT_VERSION_UNSUPPORTED");
    }

    @Test
    void malformedContractVersionFailsKafkaDeserialization() throws Exception {
        ObjectNode json = eventJson();
        json.put("eventContractVersion", "current");

        assertThatThrownBy(() -> kafkaDeserializer.deserialize(
                "transaction-scored",
                json.toString().getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(SerializationException.class);
    }

    @Test
    void diagnosticEnrichmentUnavailableRoundTripsWithoutPredictionEvidence() throws Exception {
        ObjectNode json = eventJson();
        json.put("mlPredictionEvidenceOmissionReason", "DIAGNOSTIC_ENRICHMENT_UNAVAILABLE");

        TransactionScoredEvent event = objectMapper.readValue(json.toString(), TransactionScoredEvent.class);

        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE);
        assertThat(objectMapper.writeValueAsString(event))
                .contains("\"mlPredictionEvidenceOmissionReason\":\"DIAGNOSTIC_ENRICHMENT_UNAVAILABLE\"");
    }

    @Test
    void mlEngineUnavailableRoundTripsWithObservedUnavailableMlEngine() throws Exception {
        ObjectNode json = eventWithSummary(summaryWithUnavailableMl());
        json.put("mlPredictionEvidenceOmissionReason", "ML_ENGINE_UNAVAILABLE");

        TransactionScoredEvent event = objectMapper.readValue(json.toString(), TransactionScoredEvent.class);

        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE);
    }

    @Test
    void predictionEvidenceAndOmissionReasonTogetherFailClosed() throws Exception {
        ObjectNode json = eventWithSummary();
        json.set("mlPredictionEvidence", objectMapper.valueToTree(
                evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY)
        ));
        json.put("mlPredictionEvidenceOmissionReason", "PREDICTION_NOT_ACCEPTED");

        assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
    }

    @Test
    void availableMlEngineAcceptsExactEvidenceOmissionsNotDisprovedByPublicSummary() throws Exception {
        for (MlPredictionEvidenceOmissionReason reason : List.of(
                MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING,
                MlPredictionEvidenceOmissionReason.INVALID_SCORE,
                MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE,
                MlPredictionEvidenceOmissionReason.EVIDENCE_SOURCE_INTEGRITY_FAILURE,
                MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED
        )) {
            ObjectNode json = eventWithSummary();
            json.put("mlPredictionEvidenceOmissionReason", reason.name());

            TransactionScoredEvent event = objectMapper.readValue(json.toString(), TransactionScoredEvent.class);

            assertThat(event.mlPredictionEvidence()).isNull();
            assertThat(event.mlPredictionEvidenceOmissionReason()).isEqualTo(reason);
        }
    }

    @Test
    void availableMlEngineContradictsMlEngineUnavailableOmission() throws Exception {
        ObjectNode json = eventWithSummary();
        json.put("mlPredictionEvidenceOmissionReason", "ML_ENGINE_UNAVAILABLE");

        assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_OMISSION_CONTRADICTS_ENGINE_INTELLIGENCE");
    }

    @Test
    void emittedEngineIntelligenceContradictsPipelineLevelOmission() throws Exception {
        for (MlPredictionEvidenceOmissionReason reason : List.of(
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE
        )) {
            ObjectNode json = eventWithSummary(summaryWithUnavailableMl());
            json.put("mlPredictionEvidenceOmissionReason", reason.name());

            assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                    .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_OMISSION_CONTRADICTS_ENGINE_INTELLIGENCE");
        }
    }

    @Test
    void engineDerivedOmissionRequiresObservedMlEngine() throws Exception {
        for (MlPredictionEvidenceOmissionReason reason : List.of(
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE,
                MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING,
                MlPredictionEvidenceOmissionReason.INVALID_SCORE,
                MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE,
                MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED
        )) {
            ObjectNode json = eventJson();
            json.put("mlPredictionEvidenceOmissionReason", reason.name());

            assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                    .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_OMISSION_REQUIRES_OBSERVED_ML_ENGINE");
        }
    }

    @Test
    void evidenceWithoutEngineIntelligenceFailsClosed() throws Exception {
        ObjectNode json = eventJson();
        json.remove("mlPredictionEvidenceOmissionReason");
        json.set("mlPredictionEvidence", objectMapper.valueToTree(evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY)));

        assertThatThrownBy(() -> objectMapper.readValue(json.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_REQUIRES_ENGINE_INTELLIGENCE");
    }

    @Test
    void evidenceMustMatchAssociatedEngineStatusRiskAndModelIdentity() throws Exception {
        ObjectNode wrongStatus = eventWithSummary(summaryWithUnavailableMl());
        wrongStatus.set("mlPredictionEvidence", objectMapper.valueToTree(
                evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY)
        ));
        ObjectNode wrongRisk = eventWithSummary();
        wrongRisk.set("mlPredictionEvidence", objectMapper.valueToTree(
                evidence(0.8123d, RiskLevel.MEDIUM, MODEL_IDENTITY)
        ));
        ObjectNode wrongIdentity = eventWithSummary();
        wrongIdentity.set("mlPredictionEvidence", objectMapper.valueToTree(evidence(
                0.8123d,
                RiskLevel.HIGH,
                new MlModelIdentity("python-logistic-fraud-model", "other-version", MODEL_IDENTITY.featureContractVersion())
        )));
        ObjectNode wrongBucket = eventWithSummary();
        ((ObjectNode) wrongBucket.get("engineIntelligence").get("engines").get(1))
                .put("scoreBucket", "HIGH");
        wrongBucket.set("mlPredictionEvidence", objectMapper.valueToTree(
                evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY)
        ));

        assertThatThrownBy(() -> objectMapper.readValue(wrongStatus.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INCONSISTENT");
        assertThatThrownBy(() -> objectMapper.readValue(wrongRisk.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INCONSISTENT");
        assertThatThrownBy(() -> objectMapper.readValue(wrongIdentity.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INCONSISTENT");
        assertThatThrownBy(() -> objectMapper.readValue(wrongBucket.toString(), TransactionScoredEvent.class))
                .hasRootCauseMessage("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INCONSISTENT");
    }

    @Test
    void partialEvidenceWithoutArtifactDigestFailsKafkaDeserialization() throws Exception {
        ObjectNode json = eventWithSummary();
        ObjectNode evidence = objectMapper.valueToTree(evidence(0.8123d, RiskLevel.HIGH, MODEL_IDENTITY));
        evidence.remove("modelArtifactSha256");
        json.set("mlPredictionEvidence", evidence);

        assertThatThrownBy(() -> kafkaDeserializer.deserialize(
                "transactions.scored",
                json.toString().getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(SerializationException.class);
    }

    private ObjectNode eventWithSummary() throws Exception {
        return eventWithSummary(summary());
    }

    private ObjectNode eventWithSummary(EngineIntelligenceSummary summary) throws Exception {
        ObjectNode json = eventJson();
        json.remove("mlPredictionEvidenceOmissionReason");
        json.set("engineIntelligence", objectMapper.valueToTree(summary));
        return json;
    }

    private ObjectNode eventJson() throws Exception {
        return (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(new TransactionScoredEvent(
                "evt-1",
                "txn-1",
                "corr-1",
                "cust-1",
                "acct-1",
                GENERATED_AT,
                GENERATED_AT,
                null,
                null,
                null,
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v2",
                GENERATED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                null,
                null,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                null
        )));
    }

    private EngineIntelligenceSummary summary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.VERY_HIGH,
                                List.of("MODEL_HIGH_RISK"),
                                MODEL_IDENTITY
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.SMALL
                ),
                List.of(),
                List.of()
        );
    }

    private EngineIntelligenceSummary summaryWithUnavailableMl() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.UNAVAILABLE,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_UNAVAILABLE")
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    private MlPredictionEvidence evidence(double score, RiskLevel riskLevel, MlModelIdentity identity) {
        return new MlPredictionEvidence(score, riskLevel, identity, ARTIFACT_SHA256, GENERATED_AT);
    }
}
