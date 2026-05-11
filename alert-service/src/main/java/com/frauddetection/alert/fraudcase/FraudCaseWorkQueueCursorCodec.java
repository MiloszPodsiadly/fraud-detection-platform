package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

public class FraudCaseWorkQueueCursorCodec {

    private static final int VERSION = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public FraudCaseWorkQueueCursorCodec(String secret) {
        this(new ObjectMapper(), secret);
    }

    FraudCaseWorkQueueCursorCodec(ObjectMapper objectMapper, String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalArgumentException("Fraud case work queue cursor signing secret must be configured.");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public static FraudCaseWorkQueueCursorCodec localDefault() {
        return new FraudCaseWorkQueueCursorCodec("local-test-work-queue-cursor-signing-secret");
    }

    public String encode(Sort.Order sortOrder, FraudCaseDocument document) {
        String lastValue = value(document, sortOrder.getProperty());
        String lastId = document.getCaseId();
        if (!StringUtils.hasText(lastValue) || !StringUtils.hasText(lastId)) {
            return null;
        }
        FraudCaseWorkQueueCursor cursor = new FraudCaseWorkQueueCursor(
                VERSION,
                sortOrder.getProperty(),
                sortOrder.getDirection().name().toLowerCase(Locale.ROOT),
                lastValue,
                lastId
        );
        try {
            String payload = objectMapper.writeValueAsString(cursor);
            String signature = sign(payload);
            String envelope = objectMapper.writeValueAsString(new SignedCursor(payload, signature));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new FraudCaseWorkQueueQueryException("INVALID_CURSOR", "Invalid fraud case work queue cursor.");
        }
    }

    public FraudCaseWorkQueueCursor decode(String encoded, Sort.Order requestedSort) {
        if (!StringUtils.hasText(encoded)) {
            return null;
        }
        try {
            byte[] envelopeBytes = Base64.getUrlDecoder().decode(encoded);
            SignedCursor envelope = objectMapper.readValue(envelopeBytes, SignedCursor.class);
            if (!constantTimeEquals(sign(envelope.payload()), envelope.signature())) {
                throw invalidCursor();
            }
            FraudCaseWorkQueueCursor cursor = objectMapper.readValue(envelope.payload(), FraudCaseWorkQueueCursor.class);
            if (cursor.version() != VERSION
                    || !requestedSort.getProperty().equals(cursor.sortField())
                    || !requestedSort.getDirection().name().equalsIgnoreCase(cursor.sortDirection())
                    || !StringUtils.hasText(cursor.lastValue())
                    || !StringUtils.hasText(cursor.lastId())) {
                throw invalidCursor();
            }
            return cursor;
        } catch (FraudCaseWorkQueueQueryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private String value(FraudCaseDocument document, String field) {
        return switch (field) {
            case "createdAt" -> instant(document.getCreatedAt());
            case "updatedAt" -> instant(document.getUpdatedAt());
            case "priority" -> document.getPriority() == null ? null : document.getPriority().name();
            case "riskLevel" -> document.getRiskLevel() == null ? null : document.getRiskLevel().name();
            case "caseNumber" -> document.getCaseNumber();
            default -> null;
        };
    }

    private String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        int diff = expectedBytes.length ^ actualBytes.length;
        int limit = Math.min(expectedBytes.length, actualBytes.length);
        for (int index = 0; index < limit; index++) {
            diff |= expectedBytes[index] ^ actualBytes[index];
        }
        return diff == 0;
    }

    private FraudCaseWorkQueueQueryException invalidCursor() {
        return new FraudCaseWorkQueueQueryException("INVALID_CURSOR", "Invalid fraud case work queue cursor.");
    }

    private record SignedCursor(String payload, String signature) {
    }
}
