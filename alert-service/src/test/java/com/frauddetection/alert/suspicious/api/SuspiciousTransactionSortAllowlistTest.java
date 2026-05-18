package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuspiciousTransactionSortAllowlistTest {

    @Test
    void allowedSortFieldsAreAccepted() {
        for (String field : java.util.List.of("detectedAt", "updatedAt", "riskScore", "riskLevel", "status")) {
            LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("sort", field + ",asc");

            assertThat(SuspiciousTransactionSearchQuery.from(params).sortField()).isEqualTo(field);
        }
    }

    @Test
    void defaultSortIsDetectedAtDescending() {
        SuspiciousTransactionSearchQuery query = SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>());

        assertThat(query.sortField()).isEqualTo("detectedAt");
        assertThat(query.sortDirection()).isEqualTo("DESC");
    }

    @Test
    void forbiddenAndUnknownSortFieldsAreRejected() {
        for (String field : java.util.List.of("transactionId", "customerId", "sourceEventId", "unknown")) {
            LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("sort", field + ",desc");

            assertThatThrownBy(() -> SuspiciousTransactionSearchQuery.from(params))
                    .isInstanceOf(SuspiciousTransactionReadValidationException.class);
        }
    }
}
