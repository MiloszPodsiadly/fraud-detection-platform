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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class Fdp45ReadAccessAuditResponseAdviceMarkerTest {

    @Test
    void shouldSkipClassifierAndAuditorWhenRequestAlreadyAudited() {
        ReadAccessAuditClassifier classifier = mock(ReadAccessAuditClassifier.class);
        ReadAccessAuditService auditService = mock(ReadAccessAuditService.class);
        ReadAccessAuditResponseAdvice advice = new ReadAccessAuditResponseAdvice(
                provider(classifier),
                provider(new ReadAccessResultCountExtractor()),
                provider(auditService)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases/work-queue");
        request.setAttribute(ReadAccessAuditResponseAdvice.AUDITED_ATTRIBUTE, Boolean.TRUE);
        Object body = new Object();

        Object returned = advice.beforeBodyWrite(
                body,
                null,
                MediaType.APPLICATION_JSON,
                null,
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse())
        );

        assertThat(returned).isSameAs(body);
        verify(classifier, never()).classify(any());
        verify(auditService, never()).audit(any(), any(), any(Integer.class), any());
    }

    @Test
    void shouldAuditClassifiedSensitiveReadWhenMarkerIsAbsent() {
        ReadAccessAuditService auditService = mock(ReadAccessAuditService.class);
        ReadAccessAuditResponseAdvice advice = new ReadAccessAuditResponseAdvice(
                provider(new ReadAccessAuditClassifier()),
                provider(new ReadAccessResultCountExtractor()),
                provider(auditService)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/scored");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/scored");
        request.setParameter("page", "0");
        request.setParameter("size", "25");

        advice.beforeBodyWrite(
                new PagedResponse<>(List.of("one", "two"), 2, 1, 0, 25),
                null,
                MediaType.APPLICATION_JSON,
                null,
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse())
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
}
