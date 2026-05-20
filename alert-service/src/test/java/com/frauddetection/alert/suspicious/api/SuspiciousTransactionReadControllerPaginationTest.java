package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuspiciousTransactionReadControllerPaginationTest {

    private final SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SuspiciousTransactionReadController(
                        service,
                        auditService,
                        metrics,
                        new SuspiciousTransactionQueryTelemetryClassifier(),
                        testTelemetrySink()
                ))
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();
    }

    @Test
    void defaultCursorSliceAndSizeWorks() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 20, false, null));

        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void summaryReturnsAggregateCountOutsideCursorSliceResponse() throws Exception {
        when(service.summary()).thenReturn(summaryResponse(98L));

        mockMvc.perform(get("/internal/suspicious-transactions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSuspiciousTransactions").value(98))
                .andExpect(jsonPath("$.freshness").value("FRESH"))
                .andExpect(jsonPath("$.cachedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void summaryEndpointReturnsOnlyAggregateCounter() throws Exception {
        when(service.summary()).thenReturn(summaryResponse(98L));

        mockMvc.perform(get("/internal/suspicious-transactions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSuspiciousTransactions").value(98))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.hasNext").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.pageNumber").doesNotExist())
                .andExpect(jsonPath("$.offset").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());
    }

    @Test
    void sizeMaxIsEnforced() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptySearchReturnsOk() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 20, false, null));

        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void resultWithExtraItemReturnsHasNextTrue() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(
                List.of(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))),
                1,
                true,
                "next-cursor"
        ));

        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    void responseDoesNotContainTotalElementsOrTotalPages() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 20, false, null));

        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").exists())
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.pageNumber").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist())
                .andExpect(jsonPath("$.totalCount").doesNotExist())
                .andExpect(jsonPath("$.totalSuspiciousTransactions").doesNotExist());
    }

    @Test
    void summaryEndpointDoesNotAffectCursorSearchContract() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 20, false, null));

        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.totalSuspiciousTransactions").doesNotExist());
    }

    @Test
    void summaryEndpointDoesNotExposeFraudVerdictFields() throws Exception {
        when(service.summary()).thenReturn(summaryResponse(98L));

        mockMvc.perform(get("/internal/suspicious-transactions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedFraud").doesNotExist())
                .andExpect(jsonPath("$.confirmedFraudCount").doesNotExist())
                .andExpect(jsonPath("$.fraudCount").doesNotExist())
                .andExpect(jsonPath("$.finalOutcome").doesNotExist())
                .andExpect(jsonPath("$.analystWorkload").doesNotExist())
                .andExpect(jsonPath("$.caseCount").doesNotExist());
    }

    @Test
    void invalidCursorReturnsBadRequest() throws Exception {
        when(service.search(any())).thenThrow(
                new SuspiciousTransactionReadValidationException("INVALID_SUSPICIOUS_TRANSACTION_CURSOR")
        );

        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_SUSPICIOUS_TRANSACTION_CURSOR"));
    }

    @Test
    void pageParameterIsRejected() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("page", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sortParameterIsRejected() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("sort", "updatedAt,desc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void singleMissingReturnsNotFound() throws Exception {
        when(service.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/suspicious-transactions/missing"))
                .andExpect(status().isNotFound());
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
