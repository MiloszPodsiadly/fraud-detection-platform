package com.frauddetection.alert.security.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SecurityRuleSource {

    static final List<String> ROUTE_GROUPS = List.of(
            "PublicTechnicalAuthorizationRules",
            "SessionAuthorizationRules",
            "AlertAuthorizationRules",
            "FraudCaseAuthorizationRules",
            "TransactionAuthorizationRules",
            "GovernanceAuthorizationRules",
            "AuditAuthorizationRules",
            "TrustAuthorizationRules",
            "RecoveryAuthorizationRules",
            "BffAuthorizationRules",
            "SpaFallbackAuthorizationRules",
            "DenyByDefaultAuthorizationRules"
    );

    private SecurityRuleSource() {
    }

    static Map<String, String> routeGroupSourcesExceptDenyByDefault() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String className : ROUTE_GROUPS) {
            if (!"DenyByDefaultAuthorizationRules".equals(className)) {
                sources.put(className, source("src/main/java/com/frauddetection/alert/security/config/" + className + ".java"));
            }
        }
        return sources;
    }

    static String source(String relativePath) {
        Path base = Path.of("").toAbsolutePath();
        Path candidate = base.resolve(relativePath);
        if (!Files.exists(candidate)) {
            candidate = base.resolve("alert-service").resolve(relativePath);
        }
        try {
            return Files.readString(candidate);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + candidate, exception);
        }
    }

    static String sourceFromPath(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    static Path repositoryFile(String relativePath) {
        Path base = Path.of("").toAbsolutePath();
        Path candidate = base.resolve(relativePath);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = base.resolve("..").resolve(relativePath).normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        return base.resolve(relativePath);
    }
}
