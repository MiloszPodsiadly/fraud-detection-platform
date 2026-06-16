package com.frauddetection.alert.security.error;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessAuthStateClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessMethodClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessRouteClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessTelemetryRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityTelemetryTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRecordUnauthorizedJwtAttempt() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(
                new SecurityErrorResponseWriter(objectMapper),
                metrics,
                new SecurityDeniedAccessTelemetryRecorder(meterRegistry),
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alerts");
        request.addHeader("Authorization", "Bearer token-invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("missing"));

        assertThat(meterRegistry.get("fraud.security.auth.failures")
                .tags("auth_type", "jwt", "endpoint", "alerts", "reason", "invalid_jwt")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(response.getContentAsString()).contains("reason:invalid_jwt");
    }

    @Test
    void shouldRecordMissingCredentialsAttempt() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(
                new SecurityErrorResponseWriter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()),
                metrics,
                new SecurityDeniedAccessTelemetryRecorder(meterRegistry),
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alerts");

        entryPoint.commence(request, new MockHttpServletResponse(), new InsufficientAuthenticationException("missing"));

        assertThat(meterRegistry.get("fraud.security.auth.failures")
                .tags("auth_type", "anonymous", "endpoint", "alerts", "reason", "missing_credentials")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordInvalidDemoAuthAttempt() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(
                new SecurityErrorResponseWriter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()),
                metrics,
                new SecurityDeniedAccessTelemetryRecorder(meterRegistry),
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alerts");
        request.addHeader("X-Demo-User-Id", "analyst-1");

        entryPoint.commence(request, new MockHttpServletResponse(), new BadCredentialsException("Unknown demo role."));

        assertThat(meterRegistry.get("fraud.security.auth.failures")
                .tags("auth_type", "demo", "endpoint", "alerts", "reason", "invalid_demo_auth")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordForbiddenDecisionAttempt() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(
                new SecurityErrorResponseWriter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()),
                metrics,
                new SecurityDeniedAccessTelemetryRecorder(meterRegistry),
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alerts/alert-1/decision");
        request.addHeader("Authorization", "Bearer token-readonly");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                List.of(new SimpleGrantedAuthority("alert:read"))
        ));

        handler.handle(request, new MockHttpServletResponse(), new AccessDeniedException("forbidden"));

        assertThat(meterRegistry.get("fraud.security.access.denied")
                .tags(
                        "routeGroup", "fraud_alert",
                        "outcome", "forbidden",
                        "method", "POST",
                        "authState", "authenticated"
                )
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
