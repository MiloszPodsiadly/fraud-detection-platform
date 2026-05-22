package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.observability.LinkedAlertContextMetricOutcome;
import com.frauddetection.alert.suspicious.api.observability.LinkedAlertContextMetricsRecorder;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class SuspiciousTransactionLinkedAlertContextControllerTest {

    private final SuspiciousTransactionReadService readService = mock(SuspiciousTransactionReadService.class);
    private final SuspiciousTransactionLinkedAlertContextService linkedAlertContextService =
            mock(SuspiciousTransactionLinkedAlertContextService.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final LinkedAlertContextMetricsRecorder linkedAlertContextMetricsRecorder =
            mock(LinkedAlertContextMetricsRecorder.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SuspiciousTransactionReadController(
                    readService,
                    linkedAlertContextService,
                    auditService,
                    metrics,
                    linkedAlertContextMetricsRecorder,
                    new SuspiciousTransactionQueryTelemetryClassifier(),
                    snapshot -> {
                    }
            ))
            .setControllerAdvice(new AlertServiceExceptionHandler())
            .build();

    @Test
    void linkedAlertContextUsesSuspiciousTransactionIdOnly() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.noLinkedAlert());

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_LINKED_ALERT"));

        verify(linkedAlertContextService).resolveLinkedAlertContext("suspicious-1");
        verify(linkedAlertContextMetricsRecorder).record(LinkedAlertContextMetricOutcome.NO_LINKED_ALERT);
        verify(auditService).audit(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(0),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void alertIdQueryParameterIsRejectedBeforeLookup() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert")
                        .queryParam("alertId", "alert-secret"))
                .andExpect(status().isBadRequest());

        verify(linkedAlertContextService, never()).resolveLinkedAlertContext(org.mockito.ArgumentMatchers.any());
        verify(linkedAlertContextMetricsRecorder).record(LinkedAlertContextMetricOutcome.VALIDATION_ERROR);
        verify(auditService).auditAttempt(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(ReadAccessAuditOutcome.REJECTED),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void getBodyAlertIdDoesNotSelectAlert() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.noLinkedAlert());

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertId\":\"alert-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_LINKED_ALERT"));

        verify(linkedAlertContextService).resolveLinkedAlertContext("suspicious-1");
    }

    @Test
    void missingSuspiciousTransactionReturnsSafeNotFound() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("missing"))
                .thenThrow(new SuspiciousTransactionLinkedAlertContextNotFoundException());

        mockMvc.perform(get("/internal/suspicious-transactions/missing/linked-alert"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.alertId").doesNotExist());

        verify(linkedAlertContextMetricsRecorder)
                .record(LinkedAlertContextMetricOutcome.SUSPICIOUS_TRANSACTION_NOT_FOUND);
    }

    @Test
    void unexpectedFailureReturnsTemporarilyUnavailableState() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenThrow(new IllegalStateException("mongo down for alert-secret"));

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.alertId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        verify(linkedAlertContextMetricsRecorder).record(LinkedAlertContextMetricOutcome.ERROR);
        verify(auditService).auditAttempt(
                eq(ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT),
                eq(ReadAccessResourceType.SUSPICIOUS_TRANSACTION),
                eq("suspicious-1"),
                eq(ReadAccessAuditOutcome.FAILED),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void unexpectedResolverExceptionDoesNotLogRawExceptionMessageOrIdentifiers(CapturedOutput output) throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-secret-123"))
                .thenThrow(new IllegalStateException("mongo down for alert-secret-456 customer-secret-789"));

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-secret-123/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.alertId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        verify(linkedAlertContextMetricsRecorder).record(LinkedAlertContextMetricOutcome.ERROR);
        assertNoRawResolverExceptionData(output);
    }

    @Test
    void noLinkedAlertSerializesNoAlertFields() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.noLinkedAlert());

        assertNonAvailableResponseHasNoAlertFields("NO_LINKED_ALERT");
    }

    @Test
    void linkedAlertNotFoundSerializesNoAlertFields() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.linkedAlertNotFound());

        assertNonAvailableResponseHasNoAlertFields("LINKED_ALERT_NOT_FOUND");
    }

    @Test
    void relationshipMismatchSerializesNoAlertFields() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.relationshipMismatch());

        assertNonAvailableResponseHasNoAlertFields("LINKED_ALERT_RELATIONSHIP_MISMATCH");
    }

    @Test
    void temporarilyUnavailableHasNoAlertFields() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenThrow(new IllegalStateException("store unavailable"));

        assertNonAvailableResponseHasNoAlertFields("TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void linkedAlertContextMetricsFailureDoesNotAlterNoLinkedAlertResponse() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.noLinkedAlert());
        doThrow(new IllegalStateException("metric sink unavailable for alert-secret"))
                .when(linkedAlertContextMetricsRecorder)
                .record(LinkedAlertContextMetricOutcome.NO_LINKED_ALERT);

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_LINKED_ALERT"))
                .andExpect(jsonPath("$.alertId").doesNotExist());
    }

    @Test
    void linkedAlertContextMetricsFailureDoesNotAlterAvailableResponse() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.available(
                        "alert-1",
                        "txn-1",
                        "customer-1",
                        "account-1",
                        0.93,
                        com.frauddetection.common.events.enums.RiskLevel.HIGH,
                        com.frauddetection.common.events.enums.AlertStatus.OPEN,
                        java.util.List.of("HIGH_AMOUNT"),
                        java.time.Instant.parse("2026-05-19T10:00:00Z"),
                        null,
                        "corr-1",
                        "score-1"
                ));
        doThrow(new IllegalStateException("metric sink unavailable for alert-secret"))
                .when(linkedAlertContextMetricsRecorder)
                .record(LinkedAlertContextMetricOutcome.AVAILABLE);

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LINKED_ALERT_AVAILABLE"))
                .andExpect(jsonPath("$.alertId").value("alert-1"));
    }

    @Test
    void linkedAlertContextMetricsFailureDoesNotAlterRelationshipMismatchResponse() throws Exception {
        when(linkedAlertContextService.resolveLinkedAlertContext("suspicious-1"))
                .thenReturn(AlertLinkedContextResponse.relationshipMismatch());
        doThrow(new IllegalStateException("metric sink unavailable for alert-secret"))
                .when(linkedAlertContextMetricsRecorder)
                .record(LinkedAlertContextMetricOutcome.RELATIONSHIP_MISMATCH);

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LINKED_ALERT_RELATIONSHIP_MISMATCH"))
                .andExpect(jsonPath("$.alertId").doesNotExist());
    }

    private void assertNonAvailableResponseHasNoAlertFields(String expectedState) throws Exception {
        ResultActions result = mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1/linked-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(expectedState));

        result.andExpect(jsonPath("$.alertId").doesNotExist())
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.correlationId").doesNotExist())
                .andExpect(jsonPath("$.scoreDecisionId").doesNotExist())
                .andExpect(jsonPath("$.reasonCodes").isArray())
                .andExpect(jsonPath("$.reasonCodes").isEmpty());
    }

    private void assertNoRawResolverExceptionData(CapturedOutput output) {
        org.assertj.core.api.Assertions.assertThat(output)
                .doesNotContain(
                        "mongo down",
                        "alert-secret-456",
                        "customer-secret-789",
                        "suspicious-secret-123",
                        "/internal/suspicious-transactions",
                        "linked-alert",
                        "IllegalStateException"
                );
    }
}
