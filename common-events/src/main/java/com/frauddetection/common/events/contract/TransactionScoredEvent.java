package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueDeserializer;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueNormalizer;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionScoredEvent(
        String eventId,
        String transactionId,
        String correlationId,
        String customerId,
        String accountId,
        Instant createdAt,
        Instant transactionTimestamp,
        Money transactionAmount,
        MerchantInfo merchantInfo,
        DeviceInfo deviceInfo,
        LocationInfo locationInfo,
        CustomerContext customerContext,
        Double fraudScore,
        RiskLevel riskLevel,
        String scoringStrategy,
        String modelName,
        String modelVersion,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        @JsonDeserialize(using = FeatureSnapshotWireValueDeserializer.class)
        Map<String, Object> featureSnapshot,
        Boolean alertRecommended,
        List<ScoringEvidenceItem> scoringEvidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) EngineIntelligenceSummary engineIntelligence,
        @JsonInclude(JsonInclude.Include.NON_NULL) MlPredictionEvidenceV1 mlPredictionEvidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) MlPredictionEvidenceOmissionReason mlPredictionEvidenceOmissionReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) AnalystRecommendationResult analystRecommendation
) {
    @JsonCreator
    public static TransactionScoredEvent fromJson(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("transactionTimestamp") Instant transactionTimestamp,
            @JsonProperty("transactionAmount") Money transactionAmount,
            @JsonProperty("merchantInfo") MerchantInfo merchantInfo,
            @JsonProperty("deviceInfo") DeviceInfo deviceInfo,
            @JsonProperty("locationInfo") LocationInfo locationInfo,
            @JsonProperty("customerContext") CustomerContext customerContext,
            @JsonProperty("fraudScore") Double fraudScore,
            @JsonProperty("riskLevel") RiskLevel riskLevel,
            @JsonProperty("scoringStrategy") String scoringStrategy,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("modelVersion") String modelVersion,
            @JsonProperty("inferenceTimestamp") Instant inferenceTimestamp,
            @JsonProperty("reasonCodes") List<String> reasonCodes,
            @JsonProperty("scoreDetails") Map<String, Object> scoreDetails,
            @JsonProperty("featureSnapshot")
            @JsonDeserialize(using = FeatureSnapshotWireValueDeserializer.class)
            Map<String, Object> featureSnapshot,
            @JsonProperty("alertRecommended") Boolean alertRecommended,
            @JsonProperty("scoringEvidence") List<ScoringEvidenceItem> scoringEvidence,
            @JsonProperty("engineIntelligence") EngineIntelligenceSummary engineIntelligence,
            @JsonProperty("mlPredictionEvidence") MlPredictionEvidenceV1 mlPredictionEvidence,
            @JsonProperty("mlPredictionEvidenceOmissionReason")
            MlPredictionEvidenceOmissionReason mlPredictionEvidenceOmissionReason,
            @JsonProperty("analystRecommendation") AnalystRecommendationResult analystRecommendation
    ) {
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                scoringEvidence,
                engineIntelligence,
                mlPredictionEvidence,
                mlPredictionEvidenceOmissionReason,
                analystRecommendation
        );
    }

    public TransactionScoredEvent {
        scoringEvidence = scoringEvidence == null ? List.of() : List.copyOf(scoringEvidence);
        if (featureSnapshot != null) {
            featureSnapshot = FeatureSnapshotWireValueNormalizer.normalize(featureSnapshot);
        }
        validateMlPredictionEvidenceOutcome(
                engineIntelligence,
                mlPredictionEvidence,
                mlPredictionEvidenceOmissionReason
        );
    }

    public TransactionScoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence
    ) {
        this(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                scoringEvidence,
                null,
                null,
                null,
                null
        );
    }

    public TransactionScoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence,
            EngineIntelligenceSummary engineIntelligence
    ) {
        this(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                scoringEvidence,
                engineIntelligence,
                null,
                null,
                null
        );
    }

    public TransactionScoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence,
            EngineIntelligenceSummary engineIntelligence,
            AnalystRecommendationResult analystRecommendation
    ) {
        this(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                scoringEvidence,
                engineIntelligence,
                null,
                null,
                analystRecommendation
        );
    }

    public TransactionScoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence,
            EngineIntelligenceSummary engineIntelligence,
            MlPredictionEvidenceV1 mlPredictionEvidence,
            AnalystRecommendationResult analystRecommendation
    ) {
        this(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                scoringEvidence,
                engineIntelligence,
                mlPredictionEvidence,
                null,
                analystRecommendation
        );
    }

    public TransactionScoredEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Boolean alertRecommended
    ) {
        this(
                eventId,
                transactionId,
                correlationId,
                customerId,
                accountId,
                createdAt,
                transactionTimestamp,
                transactionAmount,
                merchantInfo,
                deviceInfo,
                locationInfo,
                customerContext,
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                alertRecommended,
                List.of()
        );
    }

    private static void validateMlPredictionEvidenceOutcome(
            EngineIntelligenceSummary engineIntelligence,
            MlPredictionEvidenceV1 mlPredictionEvidence,
            MlPredictionEvidenceOmissionReason omissionReason
    ) {
        if (mlPredictionEvidence != null && omissionReason != null) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
        }
        if (mlPredictionEvidence == null) {
            validateMlPredictionEvidenceOmission(engineIntelligence, omissionReason);
            return;
        }
        if (engineIntelligence == null) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_ENGINE_INTELLIGENCE");
        }
        EngineIntelligenceEngineResult sourceEngine = engineIntelligence.engines().stream()
                .filter(engine -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(engine.engineId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_MISSING"));
        if (sourceEngine.status() != mlPredictionEvidence.engineStatus()
                || sourceEngine.riskLevel() != mlPredictionEvidence.mlRiskLevel()
                || sourceEngine.scoreBucket() != EngineIntelligenceScoreBucket.from(
                        mlPredictionEvidence.engineStatus(),
                        mlPredictionEvidence.mlScore()
                )
                || !mlPredictionEvidence.modelIdentity().equals(sourceEngine.modelIdentity())) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INCONSISTENT");
        }
    }

    private static void validateMlPredictionEvidenceOmission(
            EngineIntelligenceSummary engineIntelligence,
            MlPredictionEvidenceOmissionReason omissionReason
    ) {
        if (omissionReason == null || engineIntelligence == null) {
            return;
        }
        if (omissionReason == MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_OMISSION_CONTRADICTS_ENGINE_INTELLIGENCE");
        }
        EngineIntelligenceEngineResult sourceEngine = engineIntelligence.engines().stream()
                .filter(engine -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(engine.engineId()))
                .findFirst()
                .orElse(null);
        if (omissionReason == MlPredictionEvidenceOmissionReason.LEGITIMATE_ABSENCE) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_OMISSION_CONTRADICTS_ENGINE_INTELLIGENCE");
        }
        if (sourceEngine != null
                && sourceEngine.status() == FraudEngineStatus.AVAILABLE
                && (omissionReason == MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE
                || omissionReason == MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE)) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_OMISSION_CONTRADICTS_ENGINE_INTELLIGENCE");
        }
    }
}
