package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record OutboxRecordResponse(
        @JsonProperty("event_id")
        String eventId,
        @JsonProperty("dedupe_key")
        String dedupeKey,
        @JsonProperty("mutation_command_id")
        String mutationCommandId,
        @JsonProperty("resource_type")
        String resourceType,
        @JsonProperty("resource_id")
        String resourceId,
        @JsonProperty("event_type")
        String eventType,
        @JsonProperty("payload_hash")
        String payloadHash,
        String status,
        int attempts,
        @JsonProperty("last_error")
        String lastError,
        @JsonProperty("published_at")
        Instant publishedAt,
        @JsonProperty("confirmation_unknown_at")
        Instant confirmationUnknownAt,
        @JsonProperty("updated_at")
        Instant updatedAt
) {
    static OutboxRecordResponse from(TransactionalOutboxRecordDocument document) {
        return new OutboxRecordResponse(
                document.getEventId(),
                document.getDedupeKey(),
                document.getMutationCommandId(),
                document.getResourceType(),
                document.getResourceId(),
                document.getEventType(),
                document.getPayloadHash(),
                document.getStatus() == null ? null : document.getStatus().name(),
                document.getAttempts(),
                document.getLastError(),
                document.getPublishedAt(),
                document.getConfirmationUnknownAt(),
                document.getUpdatedAt()
        );
    }
}
