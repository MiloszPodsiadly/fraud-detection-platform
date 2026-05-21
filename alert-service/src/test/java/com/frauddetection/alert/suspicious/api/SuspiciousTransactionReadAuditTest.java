package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class SuspiciousTransactionReadAuditTest {

    private final SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final SuspiciousTransactionReadController controller =
            new SuspiciousTransactionReadController(
                    service,
                    mock(SuspiciousTransactionLinkedAlertContextService.class),
                    auditService,
                    metrics,
                    new SuspiciousTransactionQueryTelemetryClassifier(),
                    testTelemetrySink()
            );

    @Test
    void singleReadAuditMayUseSuspiciousTransactionIdAsResourceId() {
        when(service.findById("suspicious-1")).thenReturn(Optional.of(
                SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))
        ));

        controller.findById("suspicious-1", new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(1),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void suspiciousTransactionNotFoundAuditedExactlyOnce() {
        when(service.findById("missing")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.findById("missing", new MockHttpServletRequest()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        verify(auditService, times(1)).auditAttempt(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("missing"),
                eq(ReadAccessAuditOutcome.REJECTED),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void suspiciousTransactionSearchAuditedExactlyOnce() {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(
                List.of(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))),
                20,
                false,
                null
        ));

        controller.search(new org.springframework.util.LinkedMultiValueMap<>(), new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SEARCH),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq(null),
                eq(1),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void summaryEndpointIsAuditedExactlyOnceAsAggregateRead() {
        when(service.summary()).thenReturn(summaryResponse(98L));

        controller.summary(new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq(null),
                eq(1),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void summarySuccessRecordsSummaryMetric() {
        when(service.summary()).thenReturn(summaryResponse(98L));

        controller.summary(new MockHttpServletRequest());

        verify(metrics).recordSuspiciousTransactionSummaryRead("success", "FRESH");
    }

    @Test
    void summaryUnavailableRecordsSummaryMetric() {
        when(service.summary()).thenReturn(SuspiciousTransactionSummaryResponse.unavailable());

        controller.summary(new MockHttpServletRequest());

        verify(metrics).recordSuspiciousTransactionSummaryRead("unavailable", "UNAVAILABLE");
    }

    @Test
    void summaryErrorRecordsSummaryMetric() {
        when(service.summary()).thenThrow(new IllegalStateException("mongo unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.summary(new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics).recordSuspiciousTransactionSummaryRead("error", "UNAVAILABLE");
    }

    @Test
    void summaryErrorDoesNotRecordSearchMetric() {
        when(service.summary()).thenThrow(new IllegalStateException("mongo unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.summary(new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics, never()).recordSuspiciousTransactionApiSearch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void summaryMetricFailureDoesNotBreakResponse() {
        when(service.summary()).thenReturn(summaryResponse(98L));
        doThrow(new IllegalStateException("registry unavailable"))
                .when(metrics).recordSuspiciousTransactionSummaryRead("success", "FRESH");

        SuspiciousTransactionSummaryResponse response = controller.summary(new MockHttpServletRequest());

        assertThat(response.totalSuspiciousTransactions()).isEqualTo(98L);
    }

    @Test
    void searchAuditUsesContentSizeForResultCount() {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(
                List.of(
                        SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT")),
                        SuspiciousTransactionResponseContractTest.minimalResponse(List.of("RAPID_TRANSFER"))
                ),
                20,
                true,
                "cursor-2"
        ));

        controller.search(new org.springframework.util.LinkedMultiValueMap<>(), new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SEARCH),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq(null),
                eq(2),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void searchAuditDoesNotReferenceTotalElements() {
        assertThat(SuspiciousTransactionSliceResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("totalElements", "totalPages", "totalCount");
    }

    @Test
    void classifierSummaryDoesNotContainRawResponseOrPayload() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/suspicious-transactions");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/suspicious-transactions");
        request.addParameter("customerId", "customer-secret");
        request.addParameter("linkedAlertId", "alert-secret");
        request.addParameter("riskLevel", "HIGH");
        request.addParameter("size", "20");

        var target = new ReadAccessAuditClassifier().classify(request).orElseThrow();

        assertThat(target.filterBucket())
                .contains("hasCustomerFilter=true")
                .contains("hasLinkedAlertFilter=true")
                .contains("hasRiskLevelFilter=true")
                .contains("pageSizeBucket=LE_20")
                .doesNotContain("customer-secret", "alert-secret", "response", "payload", "reasonCodes");
        assertThat(target.queryHash()).isNotBlank();
    }

    @Test
    void searchAuditDoesNotIncludeFullResponseBody() {
        SuspiciousTransactionResponse response = SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"));
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(response), 20, false, null));

        controller.search(new org.springframework.util.LinkedMultiValueMap<>(), new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SEARCH),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq(null),
                eq(1),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void suspiciousTransactionReadAuditedExactlyOnce() {
        when(service.findById("suspicious-1")).thenReturn(Optional.of(
                SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))
        ));

        controller.findById("suspicious-1", new MockHttpServletRequest());

        verify(auditService, times(1)).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(1),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void linkedAlertContextReadAuditedAsSensitiveRead() {
        SuspiciousTransactionLinkedAlertContextService linkedService = mock(SuspiciousTransactionLinkedAlertContextService.class);
        SuspiciousTransactionReadController linkedController = new SuspiciousTransactionReadController(
                service,
                linkedService,
                auditService,
                metrics,
                new SuspiciousTransactionQueryTelemetryClassifier(),
                testTelemetrySink()
        );
        when(linkedService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.noLinkedAlert());

        linkedController.linkedAlertContext("suspicious-1", new MockHttpServletRequest());

        verify(auditService).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(0),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void classifierRecognizesLinkedAlertContextRouteWithoutRawAlertId() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/internal/suspicious-transactions/suspicious-secret/linked-alert"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert"
        );
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                java.util.Map.of("suspiciousTransactionId", "suspicious-secret")
        );

        var target = new ReadAccessAuditClassifier().classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.SUSPICIOUS_TRANSACTION);
        assertThat(target.resourceId()).isEqualTo("suspicious-secret");
        assertThat(target.queryHash()).isNull();
    }

    @Test
    void auditedSensitiveReadAnnotationIsMarkerOnlyForSuspiciousTransactionEndpoint() throws Exception {
        Method search = SuspiciousTransactionReadController.class.getMethod(
                "search",
                org.springframework.util.MultiValueMap.class,
                jakarta.servlet.http.HttpServletRequest.class
        );
        Method read = SuspiciousTransactionReadController.class.getMethod(
                "findById",
                String.class,
                jakarta.servlet.http.HttpServletRequest.class
        );
        Method summary = SuspiciousTransactionReadController.class.getMethod(
                "summary",
                jakarta.servlet.http.HttpServletRequest.class
        );
        Method linkedAlertContext = SuspiciousTransactionReadController.class.getMethod(
                "linkedAlertContext",
                String.class,
                jakarta.servlet.http.HttpServletRequest.class
        );

        assertThat(search.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(read.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(summary.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(linkedAlertContext.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(AuditedSensitiveRead.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("action");
    }

    private SuspiciousTransactionQueryTelemetrySink testTelemetrySink() {
        return snapshot -> {
        };
    }

    private SuspiciousTransactionSummaryResponse summaryResponse(long total) {
        java.time.Instant now = java.time.Instant.parse("2026-05-19T10:00:00Z");
        return SuspiciousTransactionSummaryResponse.fresh(total, now, now.plusSeconds(30));
    }
}
