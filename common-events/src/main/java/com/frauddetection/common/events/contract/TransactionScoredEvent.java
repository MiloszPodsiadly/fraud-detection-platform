package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueDeserializer;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueNormalizer;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import tools.jackson.databind.JsonNode;
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
            @JsonProperty("engineIntelligence") JsonNode engineIntelligence,
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
                engineIntelligence(modelVersion, engineIntelligence),
                analystRecommendation
        );
    }

    public TransactionScoredEvent {
        scoringEvidence = scoringEvidence == null ? List.of() : List.copyOf(scoringEvidence);
        if (featureSnapshot != null) {
            featureSnapshot = FeatureSnapshotWireValueNormalizer.normalize(featureSnapshot);
        }
    }

    private static EngineIntelligenceSummary engineIntelligence(String modelVersion, JsonNode engineIntelligence) {
        if (engineIntelligence == null || engineIntelligence.isNull()) {
            return null;
        }
        if (historicalV1EngineIntelligence(modelVersion, engineIntelligence)) {
            return EngineIntelligenceSummary.fromHistoricalV1JsonNode(engineIntelligence);
        }
        return EngineIntelligenceSummary.fromCurrentJsonNode(engineIntelligence);
    }

    private static boolean historicalV1EngineIntelligence(String modelVersion, JsonNode engineIntelligence) {
        JsonNode contractVersion = engineIntelligence.get("contractVersion");
        return "v1".equals(modelVersion)
                && contractVersion != null
                && !contractVersion.isNull()
                && contractVersion.asInt() == EngineIntelligenceSummary.CONTRACT_VERSION;
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
}
