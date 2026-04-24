package com.frauddetection.ingest.observability;

import com.frauddetection.common.events.observability.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationIdContext.CORRELATION_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(CorrelationIdContext.CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationIdContext.CORRELATION_ID_HEADER, correlationId);

        try (TraceContext.Scope ignored = TraceContext.open(correlationId, null, null, null)) {
            filterChain.doFilter(request, response);
        }
    }
}
