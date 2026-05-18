package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuspiciousTransactionReadControllerFilterValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SuspiciousTransactionReadController(
                        mock(SuspiciousTransactionReadService.class),
                        mock(SensitiveReadAuditService.class),
                        mock(AlertServiceMetrics.class)
                ))
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();
    }

    @Test
    void invalidFiltersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("status", "BAD"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions")
                        .queryParam("detectedFrom", "2026-05-18T11:00:00Z")
                        .queryParam("detectedTo", "2026-05-18T10:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("customerId", " "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("linkedAlertId", " "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("sort", "transactionId,desc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/suspicious-transactions").queryParam("unknown", "value"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void regexLikeCustomerIdIsTreatedAsLiteralFilter() throws Exception {
        SuspiciousTransactionSliceResponse empty = new SuspiciousTransactionSliceResponse(java.util.List.of(), 20, false, null);
        SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
        org.mockito.Mockito.when(service.search(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SuspiciousTransactionReadController(
                        service,
                        mock(SensitiveReadAuditService.class),
                        mock(AlertServiceMetrics.class)
                ))
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();

        mvc.perform(get("/internal/suspicious-transactions").queryParam("customerId", "customer-.*"))
                .andExpect(status().isOk());
    }
}
