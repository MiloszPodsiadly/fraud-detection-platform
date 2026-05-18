package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.DemoAuthHeaderParser;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.config.DemoAuthSecurityConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void unauthenticatedRequestsAreDenied() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedWithoutSuspiciousTransactionReadIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspiciousTransactionReadAuthorityAllowsListAndSingleRead() throws Exception {
        when(service.search(any())).thenReturn(new SuspiciousTransactionSliceResponse(List.of(), 0, 20, false));
        when(service.findById("suspicious-1")).thenReturn(Optional.of(
                SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))
        ));

        mockMvc.perform(get("/internal/suspicious-transactions")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-1")
                        .with(authentication(auth(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ))))
                .andExpect(status().isOk());
    }

    private UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
