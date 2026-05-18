package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuspiciousTransactionSortAllowlistTest {

    @Test
    void sortParameterIsRejectedBecauseSearchUsesFixedKeysetSort() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("sort", "detectedAt,desc");

        assertThatThrownBy(() -> SuspiciousTransactionSearchQuery.from(params))
                .isInstanceOf(SuspiciousTransactionReadValidationException.class);
    }

    @Test
    void fixedSortIsDetectedAtThenSuspiciousTransactionIdDescending() {
        SuspiciousTransactionSearchQuery query = SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>());

        assertThat(query.sortField()).isEqualTo("detectedAt");
        assertThat(query.sortDirection()).isEqualTo("DESC");
        assertThat(SuspiciousTransactionSearchQuery.TIE_BREAKER_SORT_FIELD).isEqualTo("suspiciousTransactionId");
    }

    @Test
    void unknownSortFieldsAreRejectedAsUnknownParameters() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("sort", "customerId,desc");

        assertThatThrownBy(() -> SuspiciousTransactionSearchQuery.from(params))
                .isInstanceOf(SuspiciousTransactionReadValidationException.class);
    }
}
