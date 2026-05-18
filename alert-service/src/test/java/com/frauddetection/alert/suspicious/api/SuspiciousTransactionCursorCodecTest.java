package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuspiciousTransactionCursorCodecTest {

    private final SuspiciousTransactionCursorCodec codec = new SuspiciousTransactionCursorCodec();

    @Test
    void cursorRoundTrip() {
        String token = codec.encode(Instant.parse("2026-05-18T10:00:00Z"), "suspicious-1");

        SuspiciousTransactionCursor cursor = codec.decode(token);

        assertThat(cursor.detectedAt()).isEqualTo(Instant.parse("2026-05-18T10:00:00Z"));
        assertThat(cursor.suspiciousTransactionId()).isEqualTo("suspicious-1");
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    void invalidCursorRejected() {
        assertThatThrownBy(() -> codec.decode("not-a-valid-cursor"))
                .isInstanceOf(SuspiciousTransactionReadValidationException.class);
    }

    @Test
    void blankCursorRejected() {
        assertThatThrownBy(() -> codec.decode(" "))
                .isInstanceOf(SuspiciousTransactionReadValidationException.class);
    }

    @Test
    void cursorDoesNotContainCustomerIdOrAccountId() {
        String token = codec.encode(Instant.parse("2026-05-18T10:00:00Z"), "suspicious-1");
        String payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);

        assertThat(payload)
                .contains("detectedAt")
                .contains("suspiciousTransactionId")
                .doesNotContain("customerId", "accountId", "sourceEventId", "correlationId");
    }

    @Test
    void cursorUsesDetectedAtAndSuspiciousTransactionIdOnly() {
        String token = codec.encode(Instant.parse("2026-05-18T10:00:00Z"), "suspicious-1");
        String payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);

        assertThat(payload)
                .contains("2026-05-18T10:00:00Z")
                .contains("suspicious-1")
                .doesNotContain("riskLevel", "status", "linkedAlertId");
    }
}
