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
}
