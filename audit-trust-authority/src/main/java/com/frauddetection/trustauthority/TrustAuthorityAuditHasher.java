package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class TrustAuthorityAuditHasher {

    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private TrustAuthorityAuditHasher() {
    }

    static String hash(TrustAuthorityAuditEvent event, String previousEventHash, long chainPosition) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", event.action());
        canonical.put("caller_identity", event.callerIdentity());
        canonical.put("caller_service", event.callerService());
        canonical.put("chain_position", chainPosition);
        canonical.put("event_id", event.eventId());
        canonical.put("event_schema_version", event.eventSchemaVersion());
        canonical.put("key_id", event.keyId());
        canonical.put("occurred_at", event.occurredAt() == null ? null : event.occurredAt().toString());
        canonical.put("payload_hash", event.payloadHash());
        canonical.put("previous_event_hash", previousEventHash);
        canonical.put("purpose", event.purpose());
        canonical.put("reason_code", event.reasonCode());
        canonical.put("request_id", event.requestId());
        canonical.put("result", event.result());
        try {
            byte[] bytes = CANONICAL_JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit event could not be hashed.", exception);
        }
    }
}
