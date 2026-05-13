package com.frauddetection.alert.security.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.bff")
public record BffSecurityProperties(
        boolean enabled,
        String postLogoutRedirectUri,
        String providerLogoutUri,
        String clientId
) {
    public BffSecurityProperties {
        postLogoutRedirectUri = postLogoutRedirectUri == null || postLogoutRedirectUri.isBlank()
                ? "/"
                : postLogoutRedirectUri.trim();
        providerLogoutUri = providerLogoutUri == null ? "" : providerLogoutUri.trim();
        clientId = clientId == null || clientId.isBlank() ? "analyst-console-ui" : clientId.trim();
    }
}
