package com.frauddetection.alert.security.telemetry;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryDoesNotChangeSecurityResponseTest {

    @Test
    void unauthorizedResponseSemanticsArePreserved() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = entryPoint(new SecurityDeniedAccessTelemetryRecorder(new SimpleMeterRegistry()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest("GET", "/internal/suspicious-transactions"),
                response,
                new InsufficientAuthenticationException("raw message")
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Authentication is required.")
                .doesNotContain("raw message");
    }

    @Test
    void forbiddenResponseSemanticsArePreserved() throws Exception {
        ApiAccessDeniedHandler handler = handler(new SecurityDeniedAccessTelemetryRecorder(new SimpleMeterRegistry()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("analyst-1", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest("GET", "/internal/suspicious-transactions"),
                response,
                new AccessDeniedException("raw forbidden message")
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("Insufficient permissions.")
                .doesNotContain("raw forbidden message");
        SecurityContextHolder.clearContext();
    }

    @Test
    void telemetryFailureDoesNotChangeUnauthorizedOrForbiddenResponse() throws Exception {
        SecurityDeniedAccessTelemetryRecorder throwingRecorder = new SecurityDeniedAccessTelemetryRecorder(new SimpleMeterRegistry()) {
            @Override
            public void record(SecurityDeniedAccessSnapshot snapshot) {
                throw new IllegalStateException("customerId=customer-secret cursor=cursor-secret token=token-secret");
            }
        };
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("analyst-1", null));

        entryPoint(throwingRecorder).commence(
                new MockHttpServletRequest("GET", "/internal/suspicious-transactions"),
                unauthorized,
                new InsufficientAuthenticationException("raw unauthorized")
        );
        handler(throwingRecorder).handle(
                new MockHttpServletRequest("GET", "/internal/suspicious-transactions"),
                forbidden,
                new AccessDeniedException("raw forbidden")
        );

        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(unauthorized.getContentAsString()).contains("Authentication is required.");
        assertThat(forbidden.getContentAsString()).contains("Insufficient permissions.");
        SecurityContextHolder.clearContext();
    }

    private ApiAuthenticationEntryPoint entryPoint(SecurityDeniedAccessTelemetryRecorder recorder) {
        return new ApiAuthenticationEntryPoint(
                new SecurityErrorResponseWriter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                recorder,
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
    }

    private ApiAccessDeniedHandler handler(SecurityDeniedAccessTelemetryRecorder recorder) {
        return new ApiAccessDeniedHandler(
                new SecurityErrorResponseWriter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                recorder,
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
    }
}
