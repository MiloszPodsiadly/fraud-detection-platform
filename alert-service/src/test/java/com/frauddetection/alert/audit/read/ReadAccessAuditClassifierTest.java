package com.frauddetection.alert.audit.read;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadAccessAuditClassifierTest {

    private final ReadAccessAuditClassifier classifier = new ReadAccessAuditClassifier();

    @Test
    void shouldClassifySensitiveReadWithoutRawQuery() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        request.setQueryString("customerId=cust-1&cardNumber=4111111111111111&page=2&size=25");
        request.setParameter("customerId", "cust-1");
        request.setParameter("cardNumber", "4111111111111111");
        request.setParameter("page", "2");
        request.setParameter("size", "25");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");

        ReadAccessAuditTarget target = classifier.classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.SCORED_TRANSACTION_LIST);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.SCORED_TRANSACTION);
        assertThat(target.resourceId()).isNull();
        assertThat(target.page()).isEqualTo(2);
        assertThat(target.size()).isEqualTo(25);
        assertThat(target.queryHash()).hasSize(32);
        assertThat(target.toString())
                .doesNotContain("cust-1", "4111111111111111", "customerId", "cardNumber");
    }

    @Test
    void shouldClassifyGovernanceAuditHistoryRead() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/governance/advisories/advisory-1/audit");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/governance/advisories/{eventId}/audit");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("eventId", "advisory-1"));

        ReadAccessAuditTarget target = classifier.classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_AUDIT_HISTORY);
        assertThat(target.resourceId()).isEqualTo("advisory-1");
    }

    @Test
    void shouldClassifyGovernanceAdvisoryListReadWithoutRawFilters() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/governance/advisories");
        request.setQueryString("severity=HIGH&model_version=2026-04-21.trained.v1&limit=25");
        request.setParameter("severity", "HIGH");
        request.setParameter("model_version", "2026-04-21.trained.v1");
        request.setParameter("limit", "25");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/governance/advisories");

        ReadAccessAuditTarget target = classifier.classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_LIST);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.GOVERNANCE_ADVISORY_LIST);
        assertThat(target.resourceId()).isNull();
        assertThat(target.queryHash()).hasSize(32);
        assertThat(target.toString())
                .doesNotContain("severity", "HIGH", "model_version", "2026-04-21.trained.v1");
    }

    @Test
    void shouldUseCanonicalQueryHashIndependentOfParameterOrder() {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        first.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        first.addParameter("a", "1");
        first.addParameter("b", "2");

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        second.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        second.addParameter("b", "2");
        second.addParameter("a", "1");

        assertThat(classifier.classify(first).orElseThrow().queryHash())
                .isEqualTo(classifier.classify(second).orElseThrow().queryHash());
    }

    @Test
    void shouldUseDeterministicQueryHashForRepeatedParamsAndIgnoreEmptyValues() {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        first.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        first.addParameter("tag", "beta");
        first.addParameter("tag", "");
        first.addParameter("tag", "alpha");

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        second.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        second.addParameter("tag", "alpha");
        second.addParameter("tag", "beta");

        assertThat(classifier.classify(first).orElseThrow().queryHash())
                .isEqualTo(classifier.classify(second).orElseThrow().queryHash());
    }
}
