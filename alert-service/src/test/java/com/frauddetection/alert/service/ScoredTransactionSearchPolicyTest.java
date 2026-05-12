package com.frauddetection.alert.service;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

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
        ScoredTransactionSearchCriteria criteria = policy.criteria(" Customer-1 ", " CRITICAL ", " SUSPICIOUS ");

        assertThat(criteria.query()).isEqualTo("customer-1");
        assertThat(criteria.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(criteria.alertRecommended()).isTrue();
        assertThat(criteria.hasFilters()).isTrue();
        assertThat(policy.filterBucket(criteria)).isEqualTo("combined");
    }

    @Test
    void shouldRejectInvalidEnumsAndNormalizeLowercaseValues() {
        ScoredTransactionSearchCriteria criteria = policy.criteria(null, "critical", "legitimate");

        assertThat(criteria.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(criteria.alertRecommended()).isFalse();

        assertThatThrownBy(() -> policy.criteria(null, "SEVERE", "ALL"))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
        assertThatThrownBy(() -> policy.criteria(null, "ALL", "CONFIRMED"))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
    }

    @Test
    void shouldRejectUnknownAndDuplicateParameters() {
        LinkedMultiValueMap<String, String> unknown = new LinkedMultiValueMap<>();
        unknown.add("customerId", "customer-1");
        assertThatThrownBy(() -> policy.validateParameters(unknown))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);

        LinkedMultiValueMap<String, String> duplicate = new LinkedMultiValueMap<>();
        duplicate.add("query", "customer-1");
        duplicate.add("query", "customer-2");
        assertThatThrownBy(() -> policy.validateParameters(duplicate))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
    }

    @Test
    void shouldRejectInvalidNumericParameters() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("page", "not-a-number");

        assertThatThrownBy(() -> policy.page(parameters))
                .isInstanceOf(ScoredTransactionSearchValidationException.class);
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

