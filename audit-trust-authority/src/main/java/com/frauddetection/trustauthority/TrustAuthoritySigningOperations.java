package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustSignRequest;
import com.frauddetection.trustauthority.api.TrustSignResponse;
import com.frauddetection.trustauthority.api.TrustVerifyRequest;
import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class TrustAuthoritySigningOperations {

    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final TrustAuthorityKeyRegistry keyRegistry;
    private final TrustAuthorityKeyRegistry.RegisteredKey activeKey;
    private final String authorityName;
    private final Clock clock;

    TrustAuthoritySigningOperations(
            TrustAuthorityKeyRegistry keyRegistry,
            String authorityName,
            Clock clock
    ) {
        this.keyRegistry = keyRegistry;
        this.activeKey = keyRegistry.activeKey();
        this.authorityName = authorityName;
        this.clock = clock;
    }

    TrustSignResponse sign(TrustSignRequest request) {
        Instant signedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (signedAt.isBefore(activeKey.validFrom())) {
            throw OperationFailure.request(
                    "KEY_NOT_YET_VALID",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Trust authority active signing key is not yet valid."
            );
        }
        if (activeKey.validUntil() != null && signedAt.isAfter(activeKey.validUntil())) {
            throw OperationFailure.request(
                    "KEY_EXPIRED",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Trust authority active signing key is expired."
            );
        }
        try {
            Signature signer = Signature.getInstance(TrustAuthorityKeyRegistry.ALGORITHM);
            signer.initSign(activeKey.privateKey());
            signer.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(),
                    request.chainPosition(), request.anchorId(), signedAt));
            return new TrustSignResponse(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    activeKey.keyId(),
                    TrustAuthorityKeyRegistry.ALGORITHM,
                    signedAt,
                    authorityName
            );
        } catch (GeneralSecurityException exception) {
            throw OperationFailure.internal("SIGNATURE_FAILED", "Trust authority signing failed.");
        }
    }

    Verification verify(TrustVerifyRequest request) {
        if (!"AUDIT_ANCHOR".equals(request.purpose())) {
            throw new TrustAuthorityRequestException(
                    HttpStatus.FORBIDDEN, "Unsupported trust signing purpose.");
        }
        TrustAuthorityKeyRegistry.RegisteredKey key = keyRegistry.find(request.keyId()).orElse(null);
        if (key == null) {
            return Verification.invalid("UNKNOWN_KEY");
        }
        if ("REVOKED".equals(key.status())) {
            return Verification.invalid("KEY_REVOKED");
        }
        if (request.signedAt() == null) {
            return Verification.invalid("SIGNED_AT_MISSING");
        }
        if (request.signedAt().isBefore(key.validFrom())) {
            return Verification.invalid("KEY_NOT_YET_VALID");
        }
        if (key.validUntil() != null && request.signedAt().isAfter(key.validUntil())) {
            return Verification.invalid("KEY_EXPIRED");
        }
        try {
            Signature verifier = Signature.getInstance(TrustAuthorityKeyRegistry.ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(),
                    request.chainPosition(), request.anchorId(), request.signedAt()));
            return verifier.verify(Base64.getDecoder().decode(request.signature()))
                    ? Verification.success()
                    : Verification.invalid("SIGNATURE_INVALID");
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return Verification.invalid("SIGNATURE_INVALID");
        }
    }

    String activeKeyId() {
        return activeKey.keyId();
    }

    private byte[] canonicalBytes(
            String purpose,
            String payloadHash,
            String partitionKey,
            long chainPosition,
            String anchorId,
            Instant signedAt
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("anchor_id", anchorId);
        canonical.put("chain_position", chainPosition);
        canonical.put("partition_key", partitionKey);
        canonical.put("payload_hash", payloadHash);
        canonical.put("purpose", purpose);
        canonical.put("signed_at", signedAt.toString());
        try {
            return CANONICAL_JSON.writeValueAsBytes(canonical);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Trust authority canonical payload could not be serialized.");
        }
    }

    record Verification(boolean valid, String reasonCode) {
        static Verification success() {
            return new Verification(true, null);
        }

        static Verification invalid(String reasonCode) {
            return new Verification(false, reasonCode);
        }
    }

    static final class OperationFailure extends RuntimeException {
        private final String reasonCode;
        private final RuntimeException publicException;

        private OperationFailure(String reasonCode, RuntimeException publicException) {
            this.reasonCode = reasonCode;
            this.publicException = publicException;
        }

        static OperationFailure request(String reasonCode, HttpStatus status, String message) {
            return new OperationFailure(reasonCode, new TrustAuthorityRequestException(status, message));
        }

        static OperationFailure internal(String reasonCode, String message) {
            return new OperationFailure(reasonCode, new IllegalStateException(message));
        }

        String reasonCode() {
            return reasonCode;
        }

        RuntimeException publicException() {
            return publicException;
        }
    }
}
