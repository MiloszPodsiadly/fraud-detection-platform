package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoredTransactionSearchCriteriaTest {

    @Test
    void shouldTreatBlankAndTooShortQueryAsAbsent() {
        assertThat(new ScoredTransactionSearchCriteria("  ", "ALL", "ALL").hasFilters()).isFalse();
        assertThat(new ScoredTransactionSearchCriteria("ab", "ALL", "ALL").hasFilters()).isFalse();
    }

    @Test
    void shouldTrimBoundedQueryAndKeepSelectedFilters() {
        ScoredTransactionSearchCriteria criteria = new ScoredTransactionSearchCriteria(" customer-1 ", " CRITICAL ", " SUSPICIOUS ");

        assertThat(criteria.query()).isEqualTo("customer-1");
        assertThat(criteria.riskLevel()).isEqualTo("CRITICAL");
        assertThat(criteria.classification()).isEqualTo("SUSPICIOUS");
        assertThat(criteria.hasFilters()).isTrue();
    }

    @Test
    void shouldRejectTooLongQueryWithoutEmbeddingRawQuery() {
        String rawQuery = "customer-secret-4111111111111111-".repeat(5);

        assertThatThrownBy(() -> new ScoredTransactionSearchCriteria(rawQuery, "ALL", "ALL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scored transaction query is too long.")
                .hasMessageNotContaining("4111111111111111")
                .hasMessageNotContaining("customer-secret");
    }
}
