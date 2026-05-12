package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoredTransactionSearchPolicyTest {

    private final ScoredTransactionSearchPolicy policy = new ScoredTransactionSearchPolicy();

    @Test
    void shouldAcceptBlankQueryAsAbsent() {
        assertThat(policy.criteria("", "ALL", "ALL").hasFilters()).isFalse();
        assertThat(policy.criteria("   ", "ALL", "ALL").hasFilters()).isFalse();
    }

    @Test
    void shouldRejectShortAndTooLongQuery() {
        assertThatThrownBy(() -> policy.criteria("a", "ALL", "ALL"))
                .isInstanceOf(ScoredTransactionSearchValidationException.class)
                .hasMessage("Invalid scored transaction search request.");
        assertThatThrownBy(() -> policy.criteria("ab", "ALL", "ALL"))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
        assertThatThrownBy(() -> policy.criteria("x".repeat(129), "ALL", "ALL"))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
    }

    @Test
    void shouldTrimBoundedQueryAndKeepSelectedFilters() {
        ScoredTransactionSearchCriteria criteria = policy.criteria(" customer-1 ", " CRITICAL ", " SUSPICIOUS ");

        assertThat(criteria.query()).isEqualTo("customer-1");
        assertThat(criteria.riskLevel()).isEqualTo("CRITICAL");
        assertThat(criteria.classification()).isEqualTo("SUSPICIOUS");
        assertThat(criteria.hasFilters()).isTrue();
        assertThat(policy.filterBucket(criteria)).isEqualTo("combined");
    }

    @Test
    void shouldValidatePageAndSize() {
        policy.validatePageAndSize(0, 1);
        policy.validatePageAndSize(1000, 100);

        assertThatThrownBy(() -> policy.validatePageAndSize(1001, 25))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
        assertThatThrownBy(() -> policy.validatePageAndSize(0, 101))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
    }

    @Test
    void shouldCreateCanonicalAuditQueryWithoutRawUnsupportedParameterValues() {
        String canonical = ScoredTransactionSearchPolicy.canonicalAuditQuery(Map.of(
                "query", new String[]{" Customer-123 "},
                "cardNumber", new String[]{"4111111111111111"},
                "riskLevel", new String[]{"critical"}
        ));

        assertThat(canonical).contains("query=customer-123");
        assertThat(canonical).contains("riskLevel=CRITICAL");
        assertThat(canonical).contains("cardNumber=present");
        assertThat(canonical).doesNotContain("4111111111111111");
    }
}

