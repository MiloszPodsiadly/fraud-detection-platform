package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuspiciousTransactionLinkedAlertContextControllerTest {

    private final SuspiciousTransactionReadService readService = mock(SuspiciousTransactionReadService.class);
    private final SuspiciousTransactionLinkedAlertContextService linkedAlertContextService =
            mock(SuspiciousTransactionLinkedAlertContextService.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SuspiciousTransactionReadController(
                    readService,
                    linkedAlertContextService,
                    auditService,
                    metrics,
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
        verify(metrics).recordSuspiciousTransactionLinkedAlertRead("no_linked_alert");
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

        verify(metrics).recordSuspiciousTransactionLinkedAlertRead("suspicious_transaction_not_found");
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

        verify(metrics).recordSuspiciousTransactionLinkedAlertRead("error");
    }
}
