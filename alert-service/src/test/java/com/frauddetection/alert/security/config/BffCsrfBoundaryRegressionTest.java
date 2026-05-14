package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffCsrfBoundaryRegressionTest extends BffSessionSecurityIntegrationTest {

    @Test
    void bffCookieMutationWithoutCsrfIsRejectedBeforeBusinessLayer() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
    }

    @Test
    void bffCookieMutationWithCsrfAndAuthorityReachesController() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .with(csrf())
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void statelessBearerMutationWithoutSessionCookieDoesNotNeedCsrf() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)))
                        .header("Authorization", "Bearer token-admin")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void bearerPlusSessionCookieWithoutCsrfDoesNotBypassBffCsrf() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .cookie(new Cookie("JSESSIONID", "session-1"))
                        .header("Cookie", "JSESSIONID=session-1")
                        .header("Authorization", "Bearer token-admin")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
    }

    @Test
    void bffLogoutWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/bff/logout").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());
    }
}
