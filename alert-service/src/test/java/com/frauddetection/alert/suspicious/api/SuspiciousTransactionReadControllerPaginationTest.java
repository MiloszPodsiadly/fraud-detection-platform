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
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist())
                .andExpect(jsonPath("$.totalCount").doesNotExist());
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
}
