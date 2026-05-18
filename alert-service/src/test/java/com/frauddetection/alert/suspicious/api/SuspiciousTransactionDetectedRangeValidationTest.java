package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuspiciousTransactionDetectedRangeValidationTest {

    @Test
    void detectedFromAfterDetectedToIsRejected() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("detectedFrom", "2026-05-18T11:00:00Z");
        params.add("detectedTo", "2026-05-18T10:00:00Z");

        assertThatThrownBy(() -> SuspiciousTransactionSearchQuery.from(params))
                .isInstanceOf(SuspiciousTransactionReadValidationException.class);
    }
}
