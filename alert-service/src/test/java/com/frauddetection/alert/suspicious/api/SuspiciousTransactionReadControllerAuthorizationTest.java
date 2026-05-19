package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.DemoAuthHeaderParser;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.config.DemoAuthSecurityConfig;
import com.frauddetection.alert.security.config.SecurityDeniedAccessTelemetrySliceTestConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SuspiciousTransactionReadController.class)
@Import({
        AlertSecurityConfig.class,
        DemoAuthSecurityConfig.class,
        AnalystAuthenticationFactory.class,
        DemoAuthHeaderParser.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
        AlertServiceExceptionHandler.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.demo-auth.enabled=true")
class SuspiciousTransactionReadControllerAuthorizationTest {

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean
    private SuspiciousTransactionReadService service;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private AlertServiceMetrics metrics;

    @MockBean
    private SuspiciousTransactionQueryTelemetryClassifier queryTelemetryClassifier;

    @MockBean
    private SuspiciousTransactionQueryTelemetrySink queryTelemetrySink;

    @Test
    void unauthenticatedRequestsAreDenied() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/suspicious-transactions/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void authenticatedWithoutSuspiciousTransactionReadIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/suspicious-transactions/summary")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void suspiciousTransactionReadAuthorityAllowsListSummaryAndSingleRead() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 20, false, null));
        when(service.summary()).thenReturn(new SuspiciousTransactionSummaryResponse(98L));
        when(service.findById("suspicious-1")).thenReturn(Optional.of(
                SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))
        ));

        mockMvc.perform(get("/internal/suspicious-transactions")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/suspicious-transactions/summary")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSuspiciousTransactions").value(98));
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("customer-1"))
                .andExpect(jsonPath("$.accountId").value("account-1"));
    }

    @Test
    void summaryEndpointRequiresSuspiciousTransactionRead() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions/summary")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void responseMayContainCustomerAndAccountIdentifiersOnlyBehindAuthority() throws Exception {
        when(service.findById("suspicious-1")).thenReturn(Optional.of(
                SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))
        ));

        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("customer-1"))
                .andExpect(jsonPath("$.accountId").value("account-1"));
    }

    @Test
    void unauthenticatedCannotReadCustomerAccountIdentifiers() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    void missingAuthorityCannotReadCustomerAccountIdentifiers() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist());

        verifyNoInteractions(service);
    }

    private UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
