package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionReadAuditTest {

    private final SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final SuspiciousTransactionReadController controller =
            new SuspiciousTransactionReadController(service, auditService, metrics);

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

        assertThat(search.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(read.getAnnotation(AuditedSensitiveRead.class)).isNotNull();
        assertThat(AuditedSensitiveRead.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("action");
    }
}
