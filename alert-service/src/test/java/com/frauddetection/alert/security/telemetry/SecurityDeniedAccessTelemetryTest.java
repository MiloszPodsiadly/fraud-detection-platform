package com.frauddetection.alert.security.telemetry;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapper;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.security.session.AnalystSessionController;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(AnalystSessionController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        CurrentAnalystUser.class,
        SecurityDeniedAccessTelemetryRecorder.class,
        SecurityDeniedAccessRouteClassifier.class,
        SecurityDeniedAccessMethodClassifier.class,
        SecurityDeniedAccessAuthStateClassifier.class,
        SecurityDeniedAccessTelemetryTest.MeterConfig.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.bff.enabled=true",
        "app.security.bff.provider-logout-uri=http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
        "app.security.bff.post-logout-redirect-uri=http://localhost:4173/",
        "app.security.bff.client-id=analyst-console-ui"
})
class SecurityDeniedAccessTelemetryTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    AlertServiceMetrics alertServiceMetrics;

    @MockitoBean
    OidcAnalystAuthoritiesMapper oidcAnalystAuthoritiesMapper;

    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void clearMeters() {
        if (meterRegistry instanceof SimpleMeterRegistry simpleMeterRegistry) {
            simpleMeterRegistry.clear();
        }
    }

    @Test
    void anonymousSuspiciousTransactionReadDeniedBeforeControllerRecordsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(meterRegistry.get(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC)
                .tag("routeGroup", "suspicious_transaction_read")
                .tag("outcome", "unauthorized")
                .tag("method", "GET")
                .tag("authState", "anonymous")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void authenticatedUserWithoutAuthorityRecordsForbidden() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions")
                .with(authentication(new UsernamePasswordAuthenticationToken(
                                "m.podsiadly99@gmail.com",
                                null,
                                java.util.List.of(new SimpleGrantedAuthority("transaction-monitor:read"))
                        ))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));

        assertThat(meterRegistry.get(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC)
                .tag("routeGroup", "suspicious_transaction_read")
                .tag("outcome", "forbidden")
                .tag("method", "GET")
                .tag("authState", "authenticated")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void deniedAccessMetricDoesNotContainRawRequestValues() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions/suspicious-secret-123")
                        .queryParam("cursor", "cursor-secret")
                        .queryParam("customerId", "customer-secret")
                        .header("Authorization", "Bearer secret-token-value"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(meterIds()).doesNotContain(
                "suspicious-secret-123",
                "cursor-secret",
                "customer-secret",
                "cursor=",
                "customerId=",
                "secret-token-value",
                "Authorization",
                "Bearer"
        );
    }

    @Test
    void tagKeysStayStrictlyAllowlisted() throws Exception {
        mockMvc.perform(get("/internal/suspicious-transactions"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC))
                .forEach(meter -> assertThat(meter.getId().getTags().stream().map(tag -> tag.getKey()).toList())
                        .containsExactlyInAnyOrder("routeGroup", "outcome", "method", "authState"));
    }

    private String meterIds() {
        return meterRegistry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
    }

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
