package com.frauddetection.alert.security.error;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessAuthStateClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessMethodClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessRouteClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessSnapshot;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessTelemetryRecorder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    private final SecurityErrorResponseWriter responseWriter;
    private final AlertServiceMetrics metrics;
    private final SecurityDeniedAccessTelemetryRecorder deniedAccessTelemetryRecorder;
    private final SecurityDeniedAccessRouteClassifier routeClassifier;
    private final SecurityDeniedAccessMethodClassifier methodClassifier;
    private final SecurityDeniedAccessAuthStateClassifier authStateClassifier;

    public ApiAuthenticationEntryPoint(
            SecurityErrorResponseWriter responseWriter,
            AlertServiceMetrics metrics,
            SecurityDeniedAccessTelemetryRecorder deniedAccessTelemetryRecorder,
            SecurityDeniedAccessRouteClassifier routeClassifier,
            SecurityDeniedAccessMethodClassifier methodClassifier,
            SecurityDeniedAccessAuthStateClassifier authStateClassifier
    ) {
        this.responseWriter = Objects.requireNonNull(responseWriter, "responseWriter is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.deniedAccessTelemetryRecorder = Objects.requireNonNull(deniedAccessTelemetryRecorder, "deniedAccessTelemetryRecorder is required");
        this.routeClassifier = Objects.requireNonNull(routeClassifier, "routeClassifier is required");
        this.methodClassifier = Objects.requireNonNull(methodClassifier, "methodClassifier is required");
        this.authStateClassifier = Objects.requireNonNull(authStateClassifier, "authStateClassifier is required");
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        metrics.recordAuthenticationFailure(request, authException);
        recordDeniedAccess(request, "unauthorized");
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication is required.",
                java.util.List.of("reason:" + SecurityFailureClassifier.authenticationFailureReason(request, authException))
        );
    }

    private void recordDeniedAccess(HttpServletRequest request, String outcome) {
        SecurityDeniedAccessSnapshot snapshot = new SecurityDeniedAccessSnapshot("unknown", outcome, "OTHER", "unknown");
        try {
            snapshot = new SecurityDeniedAccessSnapshot(
                    routeClassifier.classify(request == null ? null : request.getRequestURI()),
                    outcome,
                    methodClassifier.classify(request == null ? null : request.getMethod()),
                    authStateClassifier.classify(SecurityContextHolder.getContext().getAuthentication())
            );
            deniedAccessTelemetryRecorder.record(snapshot);
        } catch (RuntimeException exception) {
            log.debug(
                    "Security denied-access telemetry failed outcome={} routeGroup={} method={} authState={}",
                    snapshot.outcome(),
                    snapshot.routeGroup(),
                    snapshot.method(),
                    snapshot.authState()
            );
        }
    }
}
