package com.frauddetection.alert.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

public class BffLogoutSuccessHandler implements LogoutSuccessHandler {

    private final BffSecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final AlertServiceMetrics metrics;

    public BffLogoutSuccessHandler(BffSecurityProperties properties, ObjectMapper objectMapper, AlertServiceMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public void onLogoutSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        String logoutUrl = logoutUrl();
        metrics.recordBffLogoutRequest("success", redirectKind(logoutUrl));
        objectMapper.writeValue(response.getWriter(), Map.of("logoutUrl", logoutUrl));
    }

    private String logoutUrl() {
        if (!StringUtils.hasText(properties.providerLogoutUri())) {
            return "/";
        }
        return UriComponentsBuilder.fromUriString(properties.providerLogoutUri())
                .queryParam("client_id", properties.effectiveClientId())
                .queryParam("post_logout_redirect_uri", properties.postLogoutRedirectUri())
                .build()
                .toUriString();
    }

    private String redirectKind(String logoutUrl) {
        if (!StringUtils.hasText(logoutUrl)) {
            return "none";
        }
        if (logoutUrl.startsWith("/")) {
            return "local";
        }
        return "provider";
    }
}
