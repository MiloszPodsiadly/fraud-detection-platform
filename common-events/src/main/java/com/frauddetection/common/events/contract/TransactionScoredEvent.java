package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

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
        Map<String, Object> featureSnapshot,
        Boolean alertRecommended,
        List<ScoringEvidenceItem> scoringEvidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) EngineIntelligenceSummary engineIntelligence
) {
    public TransactionScoredEvent {
        scoringEvidence = scoringEvidence == null ? List.of() : List.copyOf(scoringEvidence);
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
