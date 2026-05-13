package com.frauddetection.alert.security.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

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

    public void validate(Environment environment) {
        if (!enabled) {
            return;
        }
        boolean localProfile = isLocalProfile(environment);
        if (!StringUtils.hasText(providerLogoutUri)) {
            if (localProfile) {
                return;
            }
            throw new IllegalStateException("BFF provider logout URI is required outside local/test profiles.");
        }
        URI providerUri = parseAbsoluteUri(providerLogoutUri, "provider logout URI");
        if (!"https".equalsIgnoreCase(providerUri.getScheme()) && !isLocalHttp(providerUri)) {
            throw new IllegalStateException("BFF provider logout URI must use HTTPS unless it targets localhost.");
        }
        if (!StringUtils.hasText(clientId) && !localProfile) {
            throw new IllegalStateException("BFF client ID is required outside local/test profiles.");
        }
        validatePostLogoutRedirectUri(localProfile);
    }

    private void validatePostLogoutRedirectUri(boolean localProfile) {
        if (!StringUtils.hasText(postLogoutRedirectUri) || postLogoutRedirectUri.startsWith("/")) {
            return;
        }
        URI redirectUri = parseAbsoluteUri(postLogoutRedirectUri, "post logout redirect URI");
        if (isAllowedApplicationOrigin(redirectUri) || (localProfile && isLocalHttp(redirectUri))) {
            return;
        }
        throw new IllegalStateException("BFF post logout redirect URI must be relative or target the local application origin.");
    }

    private URI parseAbsoluteUri(String rawUri, String label) {
        try {
            URI uri = URI.create(rawUri);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalStateException("BFF " + label + " must be an absolute URI.");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("BFF " + label + " is malformed.", ex);
        }
    }

    private boolean isAllowedApplicationOrigin(URI uri) {
        if (!isLocalhost(uri.getHost())) {
            return false;
        }
        int port = uri.getPort();
        return port == 4173 || port == 5173;
    }

    private boolean isLocalHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) && isLocalhost(uri.getHost());
    }

    private boolean isLocalhost(String host) {
        if (host == null) {
            return false;
        }
        return Set.of("localhost", "127.0.0.1", "::1").contains(host.toLowerCase(Locale.ROOT));
    }

    private boolean isLocalProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("local") || profile.equals("dev") || profile.equals("test"));
    }
}
