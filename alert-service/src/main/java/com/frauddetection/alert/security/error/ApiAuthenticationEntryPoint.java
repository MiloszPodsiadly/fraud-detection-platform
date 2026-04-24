package com.frauddetection.alert.security.error;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;
    private final AlertServiceMetrics metrics;

    public ApiAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter, AlertServiceMetrics metrics) {
        this.responseWriter = responseWriter;
        this.metrics = metrics;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        metrics.recordAuthenticationFailure(request, authException);
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication is required.",
                java.util.List.of("reason:" + SecurityFailureClassifier.authenticationFailureReason(request, authException))
        );
    }
}
