package com.frauddetection.ingest.api;

import java.time.Instant;

public record IngestTransactionResponse(
        String transactionId,
        String eventId,
        String correlationId,
        String topic,
        Instant acceptedAt,
        String status
) {
}
