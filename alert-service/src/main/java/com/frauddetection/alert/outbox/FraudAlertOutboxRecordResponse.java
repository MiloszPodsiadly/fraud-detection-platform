package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record FraudAlertOutboxRecordResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("alert_id") String alertId,
        FraudAlertOutboxStatus status,
        int attempts,
        long revision,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("published_at") Instant publishedAt,
        @JsonProperty("resolved_at") Instant resolvedAt,
        @JsonProperty("resolved_by") String resolvedBy,
        String resolution
) {
    static FraudAlertOutboxRecordResponse from(FraudAlertOutboxRecord record) {
        return new FraudAlertOutboxRecordResponse(
                record.getEventId(),
                record.getAlertId(),
                record.getStatus(),
                record.getAttempts(),
                record.getRevision(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.getPublishedAt(),
                record.getResolvedAt(),
                record.getResolvedBy(),
                record.getResolution()
        );
    }
}
