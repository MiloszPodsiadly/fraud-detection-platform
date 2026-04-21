package com.frauddetection.scoring.domain;

import java.util.Map;

public record MlModelInput(
        String transactionId,
        String customerId,
        String correlationId,
        Map<String, Object> features,
        Map<String, Object> metadata
) {

    public static MlModelInput from(FraudScoringRequest request) {
        return new MlModelInput(
                request.event().transactionId(),
                request.event().customerId(),
                request.event().correlationId(),
                request.featureSnapshot(),
                Map.of(
                        "transactionTimestamp", request.event().transactionTimestamp().toString(),
                        "currency", request.event().transactionAmount().currency(),
                        "merchantId", request.event().merchantInfo().merchantId()
                )
        );
    }
}
