package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "trust_authority_audit_events")
public record TrustAuthorityAuditEvent(
        @JsonProperty("event_schema_version")
        @Field("event_schema_version")
        Integer eventSchemaVersion,

        @JsonProperty("event_id")
        @Field("event_id")
        String eventId,

        @JsonProperty("action")
        @Field("action")
        String action,

        @JsonProperty("caller_identity")
        @Field("caller_identity")
        String callerIdentity,

        @JsonProperty("caller_service")
        @Field("caller_service")
        String callerService,

        @JsonProperty("request_id")
        @Field("request_id")
        String requestId,

        @JsonProperty("purpose")
        @Field("purpose")
        String purpose,

        @JsonProperty("payload_hash")
        @Field("payload_hash")
        String payloadHash,

        @JsonProperty("key_id")
        @Field("key_id")
        String keyId,

        @JsonProperty("result")
        @Field("result")
        String result,

        @JsonProperty("reason_code")
        @Field("reason_code")
        String reasonCode,

        @JsonProperty("occurred_at")
        @Field("occurred_at")
        Instant occurredAt,

        @JsonProperty("previous_event_hash")
        @Field("previous_event_hash")
        String previousEventHash,

        @JsonProperty("event_hash")
        @Field("event_hash")
        String eventHash,

        @JsonProperty("chain_position")
        @Field("chain_position")
        Long chainPosition
) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    TrustAuthorityAuditEvent withChain(String previousEventHash, String eventHash, long chainPosition) {
        return new TrustAuthorityAuditEvent(
                eventSchemaVersion,
                eventId,
                action,
                callerIdentity,
                callerService,
                requestId,
                purpose,
                payloadHash,
                keyId,
                result,
                reasonCode,
                occurredAt,
                previousEventHash,
                eventHash,
                chainPosition
        );
    }
}
