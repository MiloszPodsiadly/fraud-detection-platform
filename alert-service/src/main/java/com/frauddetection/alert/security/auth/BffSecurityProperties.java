package com.frauddetection.alert.security.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "app.security.bff")
public record BffSecurityProperties(
        boolean enabled,
        String postLogoutRedirectUri,
        String providerLogoutUri,
        String clientId,
        List<String> allowedProviderLogoutOrigins,
        List<String> allowedPostLogoutRedirectOrigins
) {
    public BffSecurityProperties(boolean enabled, String postLogoutRedirectUri, String providerLogoutUri, String clientId) {
        this(enabled, postLogoutRedirectUri, providerLogoutUri, clientId, List.of(), List.of());
    }

    @ConstructorBinding
    public BffSecurityProperties {
        postLogoutRedirectUri = postLogoutRedirectUri == null || postLogoutRedirectUri.isBlank()
                ? "/"
                : postLogoutRedirectUri.trim();
        providerLogoutUri = providerLogoutUri == null ? "" : providerLogoutUri.trim();
        clientId = clientId == null ? "" : clientId.trim();
        allowedProviderLogoutOrigins = normalizeOrigins(allowedProviderLogoutOrigins);
        allowedPostLogoutRedirectOrigins = normalizeOrigins(allowedPostLogoutRedirectOrigins);
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
        if (!StringUtils.hasText(clientId) && !localProfile) {
            throw new IllegalStateException("BFF client ID is required outside local/test profiles.");
        }
        URI providerUri = parseAbsoluteUri(providerLogoutUri, "provider logout URI");
        if (!"https".equalsIgnoreCase(providerUri.getScheme()) && !isLocalHttp(providerUri)) {
            throw new IllegalStateException("BFF provider logout URI must use HTTPS unless it targets localhost.");
        }
        if (!isAllowedOrigin(providerUri, allowedProviderLogoutOrigins, localProfile)) {
            throw new IllegalStateException("BFF provider logout URI origin is not allowlisted.");
        }
        validatePostLogoutRedirectUri(localProfile);
    }

    public String effectiveClientId() {
        return StringUtils.hasText(clientId) ? clientId : "analyst-console-ui";
    }

    private void validatePostLogoutRedirectUri(boolean localProfile) {
        if (!StringUtils.hasText(postLogoutRedirectUri)) {
            return;
        }
        if (postLogoutRedirectUri.startsWith("//")) {
            throw new IllegalStateException("BFF post logout redirect URI must not be protocol-relative.");
        }
        if (postLogoutRedirectUri.startsWith("/")) {
            return;
        }
        URI redirectUri = parseAbsoluteUri(postLogoutRedirectUri, "post logout redirect URI");
        if (!"https".equalsIgnoreCase(redirectUri.getScheme()) && !isLocalHttp(redirectUri)) {
            throw new IllegalStateException("BFF post logout redirect URI must use HTTPS unless it targets localhost.");
        }
        if (isAllowedOrigin(redirectUri, allowedPostLogoutRedirectOrigins, localProfile)) {
            return;
        }
        throw new IllegalStateException("BFF post logout redirect URI origin is not allowlisted.");
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

    private boolean isAllowedOrigin(URI uri, List<String> allowedOrigins, boolean localProfile) {
        if (allowedOrigins.contains(origin(uri))) {
            return true;
        }
        return localProfile && isLocalhost(uri.getHost());
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

    private String origin(URI uri) {
        int port = uri.getPort();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (port < 0) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    private boolean isLocalProfile(Environment environment) {
        String configuredProfiles = environment.getProperty("spring.profiles.active", "");
        return Arrays.stream((String.join(",", environment.getActiveProfiles()) + "," + configuredProfiles).split(","))
                .map(String::trim)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("local") || profile.equals("dev") || profile.equals("test"));
    }

    private static List<String> normalizeOrigins(List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String origin : origins) {
            if (!StringUtils.hasText(origin)) {
                continue;
            }
            normalized.add(origin.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }
}
