package com.frauddetection.alert.suspicious.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Component
public class SuspiciousTransactionCursorCodec {

    private final ObjectMapper objectMapper;

    public SuspiciousTransactionCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SuspiciousTransactionCursorCodec() {
        this(new ObjectMapper());
    }

    public String encode(Instant detectedAt, String suspiciousTransactionId) {
        if (detectedAt == null || !StringUtils.hasText(suspiciousTransactionId)) {
            return null;
        }
        try {
            String payload = objectMapper.writeValueAsString(
                    new CursorPayload(detectedAt.toString(), suspiciousTransactionId.trim())
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    public SuspiciousTransactionCursor decode(String token) {
        if (!StringUtils.hasText(token)) {
            throw invalidCursor();
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(token.trim());
            CursorPayload payload = objectMapper.readValue(payloadBytes, CursorPayload.class);
            if (!StringUtils.hasText(payload.detectedAt()) || !StringUtils.hasText(payload.suspiciousTransactionId())) {
                throw invalidCursor();
            }
            return new SuspiciousTransactionCursor(
                    Instant.parse(payload.detectedAt().trim()),
                    payload.suspiciousTransactionId().trim()
            );
        } catch (SuspiciousTransactionReadValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private SuspiciousTransactionReadValidationException invalidCursor() {
        return new SuspiciousTransactionReadValidationException("INVALID_SUSPICIOUS_TRANSACTION_CURSOR");
    }

    private record CursorPayload(String detectedAt, String suspiciousTransactionId) {
    }
}
