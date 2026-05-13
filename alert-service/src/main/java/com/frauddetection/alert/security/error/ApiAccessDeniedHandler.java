package com.frauddetection.alert.security.error;

import com.frauddetection.alert.observability.AlertServiceMetrics;
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

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;
    private final AlertServiceMetrics metrics;

    public ApiAccessDeniedHandler(SecurityErrorResponseWriter responseWriter, AlertServiceMetrics metrics) {
        this.responseWriter = responseWriter;
        this.metrics = metrics;
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
        metrics.recordAccessDenied(request, authentication);
        responseWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                "Insufficient permissions.",
                java.util.List.of("reason:" + SecurityFailureClassifier.accessDeniedReason(authentication))
        );
    }
}
