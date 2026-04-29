package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class HttpAuditTrustAuthorityClient implements AuditTrustAuthorityClient {

    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final RestClient restClient;
    private final String internalToken;

    HttpAuditTrustAuthorityClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken;
    }

    @Override
    public SignedAuditAnchorPayload sign(AuditAnchorSigningPayload payload) {
        String payloadHash = payloadHash(payload);
        try {
            TrustSignResponse response = restClient.post()
                    .uri("/api/v1/trust/sign")
                    .header("X-Internal-Trust-Token", internalToken)
                    .body(new TrustSignRequest(
                            "AUDIT_ANCHOR",
                            payloadHash,
                            payload.partitionKey(),
                            payload.chainPosition(),
                            payload.localAnchorId()
                    ))
                    .retrieve()
                    .body(TrustSignResponse.class);
            if (response == null) {
                return SignedAuditAnchorPayload.unavailable();
            }
            return new SignedAuditAnchorPayload(
                    "SIGNED",
                    response.algorithm(),
                    response.signature(),
                    response.keyId(),
                    response.signedAt(),
                    response.authority(),
                    payloadHash
            );
        } catch (RestClientException exception) {
            return SignedAuditAnchorPayload.unavailable();
        }
    }

    @Override
    public List<AuditTrustAuthorityKey> keys() {
        try {
            List<AuditTrustAuthorityKey> keys = restClient.get()
                    .uri("/api/v1/trust/keys")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return keys == null ? List.of() : keys.stream().limit(20).toList();
        } catch (RestClientException exception) {
            return List.of();
        }
    }

    @Override
    public AuditTrustSignatureVerificationResult verify(AuditAnchorSigningPayload payload, SignedAuditAnchorPayload signature) {
        if (signature == null || signature.signature() == null || signature.keyId() == null) {
            return AuditTrustSignatureVerificationResult.unsigned();
        }
        String payloadHash = payloadHash(payload);
        try {
            TrustVerifyResponse response = restClient.post()
                    .uri("/api/v1/trust/verify")
                    .header("X-Internal-Trust-Token", internalToken)
                    .body(new TrustVerifyRequest(
                            "AUDIT_ANCHOR",
                            payloadHash,
                            payload.partitionKey(),
                            payload.chainPosition(),
                            payload.localAnchorId(),
                            signature.signature(),
                            signature.keyId()
                    ))
                    .retrieve()
                    .body(TrustVerifyResponse.class);
            if (response == null) {
                return AuditTrustSignatureVerificationResult.unavailable();
            }
            if ("VALID".equals(response.status())) {
                return AuditTrustSignatureVerificationResult.valid();
            }
            if ("UNKNOWN_KEY".equals(response.reasonCode())) {
                return AuditTrustSignatureVerificationResult.unknownKey();
            }
            return AuditTrustSignatureVerificationResult.invalid(response.reasonCode());
        } catch (RestClientException exception) {
            return AuditTrustSignatureVerificationResult.unavailable();
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    private String payloadHash(AuditAnchorSigningPayload payload) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("chain_position", payload.chainPosition());
        canonical.put("external_hash", payload.externalHash());
        canonical.put("external_key", payload.externalKey());
        canonical.put("immutability_level", payload.immutabilityLevel() == null ? ExternalImmutabilityLevel.NONE.name() : payload.immutabilityLevel().name());
        canonical.put("last_event_hash", payload.lastEventHash());
        canonical.put("local_anchor_id", payload.localAnchorId());
        canonical.put("partition_key", payload.partitionKey());
        try {
            byte[] serialized = CANONICAL_JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Audit anchor signing payload could not be hashed.");
        }
    }

    private record TrustSignRequest(
            @JsonProperty("purpose") String purpose,
            @JsonProperty("payload_hash") String payloadHash,
            @JsonProperty("partition_key") String partitionKey,
            @JsonProperty("chain_position") long chainPosition,
            @JsonProperty("anchor_id") String anchorId
    ) {
    }

    private record TrustSignResponse(
            @JsonProperty("signature") String signature,
            @JsonProperty("key_id") String keyId,
            @JsonProperty("algorithm") String algorithm,
            @JsonProperty("signed_at") Instant signedAt,
            @JsonProperty("authority") String authority
    ) {
    }

    private record TrustVerifyRequest(
            @JsonProperty("purpose") String purpose,
            @JsonProperty("payload_hash") String payloadHash,
            @JsonProperty("partition_key") String partitionKey,
            @JsonProperty("chain_position") long chainPosition,
            @JsonProperty("anchor_id") String anchorId,
            @JsonProperty("signature") String signature,
            @JsonProperty("key_id") String keyId
    ) {
    }

    private record TrustVerifyResponse(
            @JsonProperty("status") String status,
            @JsonProperty("reason_code") String reasonCode
    ) {
    }
}
