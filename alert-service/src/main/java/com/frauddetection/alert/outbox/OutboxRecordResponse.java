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
        @JsonProperty("publication_confirmation_provenance")
        String publicationConfirmationProvenance,
        @JsonProperty("confirmation_unknown_at")
        Instant confirmationUnknownAt,
        @JsonProperty("updated_at")
        Instant updatedAt,
        @JsonProperty("resolution_pending")
        boolean resolutionPending,
        @JsonProperty("resolution_control_mode")
        String resolutionControlMode,
        @JsonProperty("resolution_request_id")
        String resolutionRequestId,
        @JsonProperty("resolution_proposed_outcome")
        String resolutionProposedOutcome,
        @JsonProperty("resolution_requested_by")
        String resolutionRequestedBy,
        @JsonProperty("resolution_requested_at")
        Instant resolutionRequestedAt,
        @JsonProperty("resolution_approved_by")
        String resolutionApprovedBy,
        @JsonProperty("resolution_approved_at")
        Instant resolutionApprovedAt,
        @JsonProperty("operation_status")
        String operationStatus
) {
    public static OutboxRecordResponse from(TransactionalOutboxRecordDocument document) {
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
                document.getPublicationConfirmationProvenance() == null
                        ? null
                        : document.getPublicationConfirmationProvenance().name(),
                document.getConfirmationUnknownAt(),
                document.getUpdatedAt(),
                document.isResolutionPending(),
                document.getResolutionControlMode(),
                document.getResolutionRequestId(),
                document.getResolutionProposedOutcome(),
                document.getResolutionRequestedBy(),
                document.getResolutionRequestedAt(),
                document.getResolutionApprovedBy(),
                document.getResolutionApprovedAt(),
                null
        );
    }

    public OutboxRecordResponse withOperationStatus(String value) {
        return new OutboxRecordResponse(
                eventId,
                dedupeKey,
                mutationCommandId,
                resourceType,
                resourceId,
                eventType,
                payloadHash,
                status,
                attempts,
                lastError,
                publishedAt,
                publicationConfirmationProvenance,
                confirmationUnknownAt,
                updatedAt,
                resolutionPending,
                resolutionControlMode,
                resolutionRequestId,
                resolutionProposedOutcome,
                resolutionRequestedBy,
                resolutionRequestedAt,
                resolutionApprovedBy,
                resolutionApprovedAt,
                value
        );
    }
}
