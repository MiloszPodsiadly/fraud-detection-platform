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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;
    private final AlertServiceMetrics metrics;
    private final SecurityDeniedAccessTelemetryRecorder deniedAccessTelemetryRecorder;
    private final SecurityDeniedAccessRouteClassifier routeClassifier;
    private final SecurityDeniedAccessMethodClassifier methodClassifier;
    private final SecurityDeniedAccessAuthStateClassifier authStateClassifier;

    public ApiAccessDeniedHandler(
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
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (accessDeniedException instanceof CsrfException) {
            metrics.recordBffCsrfRejection(request);
            if (request.getRequestURI() != null && request.getRequestURI().startsWith("/bff/logout")) {
                metrics.recordBffLogoutRequest("rejected", "none");
            }
        }
        recordDeniedAccess(request, authentication);
        responseWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                "Insufficient permissions.",
                java.util.List.of("reason:" + SecurityFailureClassifier.accessDeniedReason(authentication))
        );
    }

    private void recordDeniedAccess(HttpServletRequest request, org.springframework.security.core.Authentication authentication) {
        try {
            deniedAccessTelemetryRecorder.record(new SecurityDeniedAccessSnapshot(
                    routeClassifier.classify(request == null ? null : request.getRequestURI()),
                    "forbidden",
                    methodClassifier.classify(request == null ? null : request.getMethod()),
                    authStateClassifier.classify(authentication)
            ));
        } catch (RuntimeException exception) {
            // Security telemetry is diagnostic only and must never change the 403 response.
        }
    }
}
