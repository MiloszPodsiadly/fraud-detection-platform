package com.frauddetection.alert.controller;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.fraudcase.FraudCaseReadQueryPolicy;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = FraudCaseController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({FraudCaseResponseMapper.class, AlertResponseMapper.class, AlertServiceExceptionHandler.class})
class Fdp45FraudCaseLegacyDeepPaginationValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCaseManagementService service;

    @MockBean
    private AlertServiceMetrics metrics;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldAcceptListAtMaxPageForCurrentRoute() throws Exception {
        when(service.listCases(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/fraud-cases")
                        .param("page", String.valueOf(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void shouldRejectListBeyondMaxPageBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases")
                        .param("page", String.valueOf(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1))
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_PAGE_REQUEST"));
        mockMvc.perform(get("/api/v1/fraud-cases")
                        .param("page", "1000000")
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_PAGE_REQUEST"));

        verify(service, never()).listCases(any(Pageable.class));
        verify(service, never()).searchCases(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void shouldNotMapRemovedLegacyListRoute() throws Exception {
        mockMvc.perform(get("/api/fraud-cases")
                        .param("page", String.valueOf(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER))
                        .param("size", "100"))
                .andExpect(status().isNotFound());

        verify(service, never()).listCases(any(Pageable.class));
        verify(service, never()).searchCases(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }
}
