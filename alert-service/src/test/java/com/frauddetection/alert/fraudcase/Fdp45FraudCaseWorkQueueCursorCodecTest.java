package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp45FraudCaseWorkQueueCursorCodecTest {

    private static final String SECRET = "test-work-queue-cursor-secret";
    private final FraudCaseWorkQueueCursorCodec codec = new FraudCaseWorkQueueCursorCodec(SECRET);

    @Test
    void shouldRoundTripSignedOpaqueCursor() {
        Sort.Order sort = Sort.Order.desc("createdAt");
        String encoded = codec.encode(sort, caseDocument("case-2", "2026-05-10T10:00:00Z"));

        FraudCaseWorkQueueCursor decoded = codec.decode(encoded, sort);

        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.sortField()).isEqualTo("createdAt");
        assertThat(decoded.sortDirection()).isEqualTo("desc");
        assertThat(decoded.lastValue()).isEqualTo("2026-05-10T10:00:00Z");
        assertThat(decoded.lastId()).isEqualTo("case-2");
    }

    @Test
    void shouldRejectInvalidTamperedSortMismatchAndUnsupportedVersionCursors() throws Exception {
        Sort.Order sort = Sort.Order.desc("createdAt");
        String encoded = codec.encode(sort, caseDocument("case-2", "2026-05-10T10:00:00Z"));
        String tampered = encoded.substring(0, encoded.length() - 1) + (encoded.endsWith("A") ? "B" : "A");
        String unsupportedVersion = signedCursor(new FraudCaseWorkQueueCursor(99, "createdAt", "desc", "2026-05-10T10:00:00Z", "case-2"));

        assertInvalid(() -> codec.decode("not-a-cursor", sort));
        assertInvalid(() -> codec.decode(tampered, sort));
        assertInvalid(() -> codec.decode(encoded, Sort.Order.asc("createdAt")));
        assertInvalid(() -> codec.decode(unsupportedVersion, sort));
    }

    private void assertInvalid(Runnable decode) {
        assertThatThrownBy(decode::run)
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_CURSOR");
    }

    private FraudCaseDocument caseDocument(String caseId, String createdAt) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseNumber("FC-" + caseId);
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setCreatedAt(Instant.parse(createdAt));
        document.setUpdatedAt(Instant.parse(createdAt));
        return document;
    }

    private String signedCursor(FraudCaseWorkQueueCursor cursor) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String payload = objectMapper.writeValueAsString(cursor);
        String envelope = objectMapper.writeValueAsString(Map.of("payload", payload, "signature", signature(payload)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
    }

    private String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
