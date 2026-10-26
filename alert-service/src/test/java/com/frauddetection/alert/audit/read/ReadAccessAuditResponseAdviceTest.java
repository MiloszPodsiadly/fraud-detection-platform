package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.PagedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadAccessAuditResponseAdviceTest {

    @Test
    void missingAuditServiceFailsClosedBeforeReturningSensitiveBody() throws Exception {
        ObjectProvider<ReadAccessAuditService> missingAuditor = mock(ObjectProvider.class);
        when(missingAuditor.getIfAvailable()).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        SensitiveReadAuditPolicy failClosedPolicy = mock(SensitiveReadAuditPolicy.class);
        when(failClosedPolicy.failClosed()).thenReturn(true);
        ReadAccessAuditResponseAdvice advice = new ReadAccessAuditResponseAdvice(
                provider(new ReadAccessAuditClassifier()), provider(new ReadAccessResultCountExtractor()),
                missingAuditor, provider(failClosedPolicy));

        Object result = advice.beforeBodyWrite("sensitive-customer-body", null, MediaType.TEXT_PLAIN, null,
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(response));

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Sensitive read audit unavailable.")
                .doesNotContain("sensitive-customer-body");
    }

    @Test
    void shouldAuditSuccessfulSensitiveReadFromResponseBody() {
        ReadAccessAuditService auditService = mock(ReadAccessAuditService.class);
        ReadAccessAuditResponseAdvice advice = new ReadAccessAuditResponseAdvice(
                provider(new ReadAccessAuditClassifier()),
                provider(new ReadAccessResultCountExtractor()),
                provider(auditService),
                provider(failOpenPolicy())
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        request.setParameter("page", "0");
        request.setParameter("size", "25");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        advice.beforeBodyWrite(
                new PagedResponse<>(List.of("one", "two"), 2, 1, 0, 25),
                null,
                MediaType.APPLICATION_JSON,
                null,
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response)
        );

        verify(auditService).audit(
                org.mockito.ArgumentMatchers.argThat(target ->
                        target.endpointCategory() == ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH
                                && target.page() == 0
                                && target.size() == 25),
                org.mockito.ArgumentMatchers.eq(ReadAccessAuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }

    private static SensitiveReadAuditPolicy failOpenPolicy() {
        SensitiveReadAuditPolicy policy = mock(SensitiveReadAuditPolicy.class);
        when(policy.failClosed()).thenReturn(false);
        return policy;
    }
}
