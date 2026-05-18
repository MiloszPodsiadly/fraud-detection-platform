package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
                .standaloneSetup(new SuspiciousTransactionReadController(service, auditService, metrics))
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();
    }

    @Test
    void defaultPageAndSizeWorks() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 0, 20, false));

        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void sizeMaxIsEnforced() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyPageReturnsOk() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 0, 20, false));

        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void resultWithExtraItemReturnsHasNextTrue() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(
                List.of(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))),
                0,
                1,
                true
        ));

        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void responseDoesNotContainTotalElementsOrTotalPages() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 0, 20, false));

        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").exists())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist());
    }

    @Test
    void singleMissingReturnsNotFound() throws Exception {
        when(service.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/suspicious-transactions/missing"))
                .andExpect(status().isNotFound());
    }
}
